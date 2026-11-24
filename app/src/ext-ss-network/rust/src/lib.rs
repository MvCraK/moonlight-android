// EXTENSION DEVELOPMENT [EXT-SS-NETWORK] [ADDED]
// UDP media is encrypted/decrypted in place on Moonlight's own sockets (see
// jni/ss_hook.c); only the few setup TCP connections use the loopback relay below.
use aes::{
    cipher::{BlockDecrypt, BlockEncrypt, KeyInit},
    Aes128, Aes256, Block,
};
use bytes::{Bytes, BytesMut};
use serde::Deserialize;
use shadowsocks::{
    config::{ServerAddr, ServerType},
    context::{Context, SharedContext},
    crypto::{
        v1::{random_iv_or_salt, Cipher},
        v2::udp::UdpCipher,
        CipherCategory, CipherKind,
    },
    relay::{
        socks5::Address,
        udprelay::{
            crypto_io::{decrypt_server_payload, encrypt_client_payload},
            options::UdpSocketControlData,
        },
    },
    ProxyClientStream, ServerConfig,
};
use std::{
    cell::RefCell,
    ffi::CStr,
    net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, ToSocketAddrs},
    os::raw::c_char,
    slice,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::{copy_bidirectional, AsyncWriteExt},
    net::TcpListener,
    runtime::Runtime,
    time::timeout,
};

const AEAD2022_CLIENT_TYPE: u8 = 0;
const AEAD2022_SERVER_TYPE: u8 = 1;
const AEAD2022_MAX_TS_DIFF: u64 = 30;

thread_local! {
    static UDP_ENC_BUF: RefCell<BytesMut> = RefCell::new(BytesMut::with_capacity(2048));
}

#[inline]
fn unix_now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// Pre-expanded AES-ECB cipher for SIP022 16-byte UDP packet header encryption.
/// Eliminates per-packet `Aes128::new_from_slice` key schedule expansion.
enum HeaderEcb {
    None,
    Aes128(Aes128),
    Aes256(Aes256),
}
impl HeaderEcb {
    fn new(method: CipherKind, key: &[u8], identity_keys: &[Bytes]) -> Self {
        if !identity_keys.is_empty() {
            return Self::None; // Multi-user EIH uses crypto_io fallback.
        }
        match method {
            CipherKind::AEAD2022_BLAKE3_AES_128_GCM => Aes128::new_from_slice(key)
                .map(Self::Aes128)
                .unwrap_or(Self::None),
            CipherKind::AEAD2022_BLAKE3_AES_256_GCM => Aes256::new_from_slice(key)
                .map(Self::Aes256)
                .unwrap_or(Self::None),
            _ => Self::None,
        }
    }
    #[inline]
    fn is_some(&self) -> bool {
        !matches!(self, Self::None)
    }
    #[inline]
    fn encrypt_header(&self, header: &mut [u8]) {
        let block = Block::from_mut_slice(header);
        match self {
            Self::Aes128(c) => c.encrypt_block(block),
            Self::Aes256(c) => c.encrypt_block(block),
            Self::None => unreachable!(),
        }
    }
    #[inline]
    fn decrypt_header(&self, header: &mut [u8]) {
        let block = Block::from_mut_slice(header);
        match self {
            Self::Aes128(c) => c.decrypt_block(block),
            Self::Aes256(c) => c.decrypt_block(block),
            Self::None => unreachable!(),
        }
    }
}

#[derive(Deserialize)]
struct Node {
    server: String,
    port: u16,
    cipher: String,
    password: String,
    #[serde(default)]
    tfo: bool,
}
impl Node {
    fn config(self) -> Option<Endpoint> {
        if self.server.is_empty()
            || self.port == 0
            || self.password.is_empty()
            || !matches!(
                self.cipher.as_str(),
                "aes-128-gcm"
                    | "aes-256-gcm"
                    | "chacha20-ietf-poly1305"
                    | "2022-blake3-aes-128-gcm"
                    | "2022-blake3-aes-256-gcm"
                    | "2022-blake3-chacha20-poly1305"
            )
        {
            return None;
        }
        let address = match self.server.parse::<IpAddr>() {
            Ok(ip) => ServerAddr::SocketAddr(SocketAddr::new(ip, self.port)),
            Err(_) => ServerAddr::DomainName(self.server, self.port),
        };
        Some(Endpoint {
            config: ServerConfig::new(address, self.password, self.cipher.parse().ok()?).ok()?,
            tfo: self.tfo,
        })
    }
}

#[derive(Clone)]
struct Endpoint {
    config: ServerConfig,
    tfo: bool,
}

/// Shared configuration and resolved server endpoint for a UDP route.
struct UdpNode {
    method: CipherKind,
    key: Box<[u8]>,
    identity_keys: Box<[Bytes]>,
    header_ecb: HeaderEcb,
    ctx: SharedContext,
    host: ServerAddr,
    resolved: Mutex<Option<SocketAddrV4>>,
}
impl UdpNode {
    fn new(endpoint: &Endpoint, ctx: SharedContext) -> Arc<Self> {
        let config = &endpoint.config;
        let method = config.method();
        let key: Box<[u8]> = config.key().into();
        let identity_keys: Box<[Bytes]> = config.identity_keys().into();
        let header_ecb = HeaderEcb::new(method, &key, &identity_keys);
        Arc::new(Self {
            method,
            key,
            identity_keys,
            header_ecb,
            ctx,
            host: config.addr().clone(),
            resolved: Mutex::new(None),
        })
    }
    // Moonlight's media sockets are AF_INET, so the node must be reachable over IPv4.
    fn server(&self) -> Option<SocketAddrV4> {
        let mut cached = self.resolved.lock().unwrap();
        if cached.is_none() {
            *cached = match &self.host {
                ServerAddr::SocketAddr(SocketAddr::V4(addr)) => Some(*addr),
                ServerAddr::SocketAddr(SocketAddr::V6(_)) => None,
                ServerAddr::DomainName(host, port) => {
                    (host.as_str(), *port).to_socket_addrs().ok()?.find_map(|a| match a {
                        SocketAddr::V4(v4) => Some(v4),
                        SocketAddr::V6(_) => None,
                    })
                }
            };
        }
        *cached
    }
    fn overhead(&self) -> usize {
        match self.method.category() {
            CipherCategory::Aead2022 => {
                let nonce_len = match self.method {
                    CipherKind::AEAD2022_BLAKE3_AES_128_GCM
                    | CipherKind::AEAD2022_BLAKE3_AES_256_GCM => 0,
                    _ => self.method.nonce_len(),
                };
                let eih_len = match self.method {
                    CipherKind::AEAD2022_BLAKE3_AES_128_GCM
                    | CipherKind::AEAD2022_BLAKE3_AES_256_GCM => self.identity_keys.len() * 16,
                    _ => 0,
                };
                // nonce + session_id(8) + packet_id(8) + eih + type(1) + ts(8) + pad_len(2) + ipv4_addr(7) + tag
                nonce_len + 8 + 8 + eih_len + 1 + 8 + 2 + 7 + self.method.tag_len()
            }
            _ => self.method.salt_len() + 7 + self.method.tag_len(),
        }
    }
}

/// Per-socket UDP session state shared with C.
/// Each Moonlight socket owns its own SIP022 `client_session_id`, monotonic `packet_id`,
/// pre-derived client `UdpCipher`, and inline single-slot server `UdpCipher` cache.
pub struct UdpFlow {
    node: Arc<UdpNode>,
    client_session_id: u64,
    packet_id: AtomicU64,
    client_cipher: Option<UdpCipher>,
    server_cache: Mutex<Option<(u64, UdpCipher)>>,
}
impl UdpFlow {
    fn new(node: Arc<UdpNode>) -> Self {
        let mut sid = 0u64;
        while sid == 0 {
            let mut bytes = [0u8; 8];
            random_iv_or_salt(&mut bytes);
            sid = u64::from_be_bytes(bytes);
        }
        let client_cipher = if node.header_ecb.is_some() {
            Some(UdpCipher::new(node.method, &node.key, sid))
        } else {
            None
        };
        Self {
            node,
            client_session_id: sid,
            packet_id: AtomicU64::new(0),
            client_cipher,
            server_cache: Mutex::new(None),
        }
    }
}

struct Routes {
    home_tcp: Endpoint,
    company: Endpoint,
    home_udp_node: Arc<UdpNode>,
    company_udp_node: Arc<UdpNode>,
    ctx: SharedContext,
}

static ROUTES: Mutex<Option<Arc<Routes>>> = Mutex::new(None);
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

#[no_mangle]
pub unsafe extern "C" fn vss_config(json: *const c_char) -> bool {
    if json.is_null() {
        *ROUTES.lock().unwrap() = None;
        return true;
    }
    let Some(routes) = (|| {
        let [tcp, udp, company]: [Node; 3] =
            serde_json::from_slice(CStr::from_ptr(json).to_bytes()).ok()?;
        let (home_tcp, home_udp, company) = (tcp.config()?, udp.config()?, company.config()?);
        let ctx = Context::new_shared(ServerType::Local);
        Some(Routes {
            home_udp_node: UdpNode::new(&home_udp, ctx.clone()),
            company_udp_node: UdpNode::new(&company, ctx.clone()),
            home_tcp,
            company,
            ctx,
        })
    })() else {
        return false;
    };
    *ROUTES.lock().unwrap() = Some(Arc::new(routes));
    true
}

fn current() -> Option<Arc<Routes>> {
    ROUTES.lock().unwrap().clone()
}

/// Selects the UDP node for an IPv4 destination (host order), resolves its server,
/// and creates a per-socket UDP flow (with a unique SIP022 session ID).
/// Caller releases with `vss_udp_release`.
#[no_mangle]
pub unsafe extern "C" fn vss_udp_acquire(ip: u32, server_ip: *mut u32, server_port: *mut u16) -> *const UdpFlow {
    let Some(routes) = current() else { return std::ptr::null() };
    let node = match ip >> 8 {
        0x0a0000 => &routes.home_udp_node,
        0x0a0101 => &routes.company_udp_node,
        _ => return std::ptr::null(),
    };
    let Some(server) = node.server() else { return std::ptr::null() };
    *server_ip = u32::from(*server.ip());
    *server_port = server.port();
    Box::into_raw(Box::new(UdpFlow::new(node.clone())))
}

#[no_mangle]
pub unsafe extern "C" fn vss_udp_release(flow: *const UdpFlow) {
    if !flow.is_null() {
        drop(Box::from_raw(flow as *mut UdpFlow));
    }
}

#[no_mangle]
pub unsafe extern "C" fn vss_udp_overhead(flow: *const UdpFlow) -> usize {
    (*flow).node.overhead()
}

/// Encrypts one outbound UDP datagram into `out`.
/// Supports both legacy AEAD (`salt | AEAD(ATYP_V4 | payload) | tag`) and
/// AEAD-2022 (`2022-blake3-*`) with thread-local cached session subkeys.
#[no_mangle]
pub unsafe extern "C" fn vss_udp_encrypt(
    flow: *const UdpFlow,
    ip: u32,
    port: u16,
    data: *const u8,
    len: usize,
    out: *mut u8,
    cap: usize,
) -> isize {
    let flow = &*flow;
    let node = &flow.node;
    if node.method.category() == CipherCategory::Aead2022 {
        // Fast zero-allocation direct path for single-user 2022-blake3-aes-{128,256}-gcm
        // with non-empty payloads (padding_size == 0 per SIP022).
        if len > 0 {
            if let Some(ref cipher) = flow.client_cipher {
                let total = 34 + len + 16; // 16 hdr + 1 type + 8 ts + 2 pad + 7 ipv4 + payload + 16 tag
                if total > cap {
                    return -1;
                }
                let pkt = slice::from_raw_parts_mut(out, total);
                let pid = flow.packet_id.fetch_add(1, Ordering::Relaxed);
                pkt[0..8].copy_from_slice(&flow.client_session_id.to_be_bytes());
                pkt[8..16].copy_from_slice(&pid.to_be_bytes());
                pkt[16] = AEAD2022_CLIENT_TYPE;
                pkt[17..25].copy_from_slice(&unix_now_secs().to_be_bytes());
                pkt[25..27].copy_from_slice(&0u16.to_be_bytes());
                pkt[27] = 1; // ATYP_V4
                pkt[28..32].copy_from_slice(&ip.to_be_bytes());
                pkt[32..34].copy_from_slice(&port.to_be_bytes());
                pkt[34..34 + len].copy_from_slice(slice::from_raw_parts(data, len));
                let (header, body) = pkt.split_at_mut(16);
                cipher.encrypt_packet(&header[4..16], body);
                node.header_ecb.encrypt_header(header);
                return total as isize;
            }
        }
        let payload = if len > 0 { slice::from_raw_parts(data, len) } else { &[] };
        let addr = Address::SocketAddress(SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::from(ip), port)));
        let mut control = UdpSocketControlData::default();
        control.client_session_id = flow.client_session_id;
        control.packet_id = flow.packet_id.fetch_add(1, Ordering::Relaxed);
        return UDP_ENC_BUF.with(|cell| {
            let mut dst = cell.borrow_mut();
            dst.clear();
            encrypt_client_payload(
                &node.ctx,
                node.method,
                &node.key,
                &addr,
                &control,
                &node.identity_keys,
                payload,
                &mut dst,
            );
            if dst.len() > cap {
                return -1;
            }
            slice::from_raw_parts_mut(out, dst.len()).copy_from_slice(&dst);
            dst.len() as isize
        });
    }
    let salt_len = node.method.salt_len();
    let total = len + node.overhead();
    if total > cap {
        return -1;
    }
    let out = slice::from_raw_parts_mut(out, total);
    let (salt, body) = out.split_at_mut(salt_len);
    random_iv_or_salt(salt);
    body[0] = 1;
    body[1..5].copy_from_slice(&ip.to_be_bytes());
    body[5..7].copy_from_slice(&port.to_be_bytes());
    if len > 0 {
        body[7..7 + len].copy_from_slice(slice::from_raw_parts(data, len));
    }
    Cipher::new(node.method, &node.key, salt).encrypt_packet(body);
    total as isize
}

/// Decrypts an inbound UDP datagram in place.
/// On success, writes the payload byte offset within `buf` to `*offset`,
/// the original IPv4 sender to `*ip` / `*port`, and returns the payload length.
#[no_mangle]
pub unsafe extern "C" fn vss_udp_decrypt_view(
    flow: *const UdpFlow,
    buf: *mut u8,
    len: usize,
    offset: *mut usize,
    ip: *mut u32,
    port: *mut u16,
) -> isize {
    let flow = &*flow;
    let node = &flow.node;
    if node.method.category() == CipherCategory::Aead2022 {
        // Fast zero-copy direct path for single-user 2022-blake3-aes-{128,256}-gcm:
        // uses pre-expanded AES-ECB header cipher, inline single-slot server UdpCipher
        // cache (zero LruCache/hashing), and returns *offset with zero copy_within.
        if node.header_ecb.is_some() {
            // Minimum Server->Client IPv4 packet: 16 hdr + 1 type + 8 ts + 8 csid + 2 pad + 7 ipv4 + 16 tag = 58
            if len < 58 {
                return -1;
            }
            let packet = slice::from_raw_parts_mut(buf, len);
            let (header, body) = packet.split_at_mut(16);
            node.header_ecb.decrypt_header(header);
            let server_sid = u64::from_be_bytes(header[0..8].try_into().unwrap());
            let nonce = &header[4..16];
            let ok = {
                let mut cache = flow.server_cache.lock().unwrap();
                let need_new = match *cache {
                    Some((sid, _)) => sid != server_sid,
                    None => true,
                };
                if need_new {
                    *cache = Some((server_sid, UdpCipher::new(node.method, &node.key, server_sid)));
                }
                cache.as_ref().unwrap().1.decrypt_packet(nonce, body)
            };
            if !ok {
                return -1;
            }
            let plain = &body[..body.len() - 16];
            if plain[0] != AEAD2022_SERVER_TYPE {
                return -1;
            }
            let ts = u64::from_be_bytes(plain[1..9].try_into().unwrap());
            if unix_now_secs().abs_diff(ts) > AEAD2022_MAX_TS_DIFF {
                return -1;
            }
            let csid = u64::from_be_bytes(plain[9..17].try_into().unwrap());
            if csid != flow.client_session_id {
                return -1;
            }
            let pad_len = u16::from_be_bytes([plain[17], plain[18]]) as usize;
            let addr_off = 19usize.saturating_add(pad_len);
            if addr_off + 7 > plain.len() || plain[addr_off] != 1 {
                return -1;
            }
            *ip = u32::from_be_bytes(plain[addr_off + 1..addr_off + 5].try_into().unwrap());
            *port = u16::from_be_bytes([plain[addr_off + 5], plain[addr_off + 6]]);
            *offset = 16 + addr_off + 7;
            return (plain.len() - (addr_off + 7)) as isize;
        }
        let packet = slice::from_raw_parts_mut(buf, len);
        let Ok((plain_len, addr, control)) =
            decrypt_server_payload(&node.ctx, node.method, &node.key, packet)
        else {
            return -1;
        };
        if let Some(ctrl) = control {
            if ctrl.client_session_id != flow.client_session_id {
                return -1;
            }
        }
        let Address::SocketAddress(SocketAddr::V4(v4)) = addr else {
            return -1;
        };
        *ip = u32::from(*v4.ip());
        *port = v4.port();
        *offset = 0;
        return plain_len as isize;
    }
    let (salt_len, tag_len) = (node.method.salt_len(), node.method.tag_len());
    if len < node.overhead() {
        return -1;
    }
    let packet = slice::from_raw_parts_mut(buf, len);
    let (salt, body) = packet.split_at_mut(salt_len);
    if !Cipher::new(node.method, &node.key, salt).decrypt_packet(body) {
        return -1;
    }
    let plain = &body[..body.len() - tag_len];
    if plain[0] != 1 {
        return -1; // The host only ever answers from an IPv4 address.
    }
    *ip = u32::from_be_bytes(plain[1..5].try_into().unwrap());
    *port = u16::from_be_bytes([plain[5], plain[6]]);
    *offset = salt_len + 7;
    (plain.len() - 7) as isize
}

/// Decrypts a datagram in place and moves its payload to the start of `buf`.
/// Returns the payload length and the original IPv4 sender, or -1 to drop it.
#[no_mangle]
pub unsafe extern "C" fn vss_udp_decrypt(
    flow: *const UdpFlow,
    buf: *mut u8,
    len: usize,
    ip: *mut u32,
    port: *mut u16,
) -> isize {
    let mut start = 0usize;
    let payload = vss_udp_decrypt_view(flow, buf, len, &mut start, ip, port);
    if payload < 0 {
        return -1;
    }
    let payload = payload as usize;
    if start > 0 {
        let packet = slice::from_raw_parts_mut(buf, len);
        packet.copy_within(start..start + payload, 0);
    }
    payload as isize
}

fn loopback(port: u16) -> SocketAddr {
    (Ipv4Addr::LOCALHOST, port).into()
}

/// Opens a one-shot loopback TCP relay that accepts only `source`.
fn forward_tcp(ip: u32, port: u16, source: u16) -> Option<u16> {
    let routes = current()?;
    let node = match ip >> 8 {
        0x0a0000 => &routes.home_tcp,
        0x0a0101 => &routes.company,
        _ => return None,
    }
    .clone();
    let ctx = routes.ctx.clone();
    let addr = Address::SocketAddress((Ipv4Addr::from(ip), port).into());
    let runtime = RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_all()
            .thread_name("moonlight-ss")
            .on_thread_start(|| {
                #[cfg(target_os = "android")]
                unsafe {
                    libc::setpriority(libc::PRIO_PROCESS, 0, -8);
                }
            })
            .build()
            .unwrap()
    });
    let _guard = runtime.enter();
    let listener = std::net::TcpListener::bind(loopback(0)).ok()?;
    let local = listener.local_addr().ok()?.port();
    listener.set_nonblocking(true).ok()?;
    let listener = TcpListener::from_std(listener).ok()?;
    runtime.spawn(async move {
        let accept = async {
            loop {
                let (s, peer) = listener.accept().await.ok()?;
                if peer == loopback(source) {
                    return Some(s);
                }
            }
        };
        let Ok(Some(mut client)) = timeout(Duration::from_secs(10), accept).await else {
            return;
        };
        drop(listener);
        let _ = client.set_nodelay(true);
        let mut opts = shadowsocks::net::ConnectOpts::default();
        opts.tcp.nodelay = true;
        opts.tcp.fastopen = node.tfo;
        let Ok(Ok(mut proxy)) = timeout(
            Duration::from_secs(10),
            ProxyClientStream::connect_with_opts(ctx, &node.config, addr, &opts),
        )
        .await
        else {
            return;
        };
        // Flush the SS handshake (Salt + target addr) immediately so server-speaks-first
        // protocols (e.g. VoiceFnConnection / FnControl) receive the server greeting
        // instead of deadlocking on lazy client-speaks-first connect.
        if timeout(Duration::from_secs(10), proxy.write(&[]))
            .await
            .ok()
            .and_then(Result::ok)
            .is_none()
        {
            return;
        }
        let _ = copy_bidirectional(&mut client, &mut proxy).await;
    });
    Some(local)
}

#[no_mangle]
pub extern "C" fn vss_open_tcp(ip: u32, port: u16, source: u16) -> u16 {
    forward_tcp(ip, port, source).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use shadowsocks::{crypto::CipherKind, ProxyListener, ProxySocket};
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpSocket,
    };

    fn node_json(port: u16, key: &str) -> serde_json::Value {
        serde_json::json!({"server":"127.0.0.1","port":port,"cipher":"aes-256-gcm","password":key})
    }

    fn test_lock() -> &'static tokio::sync::Mutex<()> {
        static LOCK: OnceLock<tokio::sync::Mutex<()>> = OnceLock::new();
        LOCK.get_or_init(|| tokio::sync::Mutex::new(()))
    }

    // Distinct listeners and keys make swapping either home subscription observable.
    #[tokio::test(flavor = "multi_thread")]
    async fn split_routes_preserve_destinations_and_return_traffic() {
        let _guard = test_lock().lock().await;
        timeout(Duration::from_secs(10), async {
            let server = Context::new_shared(ServerType::Server);
            let mut nodes = Vec::new();
            for slot in 0..3 {
                let key = format!("local-test-{slot}");
                let mut config =
                    ServerConfig::new(loopback(0), &key, CipherKind::AES_256_GCM).unwrap();
                let tcp = ProxyListener::bind(server.clone(), &config).await.unwrap();
                let addr = tcp.local_addr().unwrap();
                config.set_addr(addr);
                let udp = ProxySocket::bind(server.clone(), &config).await.unwrap();
                nodes.push(node_json(addr.port(), &key));
                let target_ip = if slot == 2 { "10.1.1.70" } else { "10.0.0.80" };
                if slot != 1 {
                    tokio::spawn(async move {
                        let (mut stream, _) = tcp.accept().await.unwrap();
                        assert_eq!(
                            stream.handshake().await.unwrap().to_string(),
                            format!("{target_ip}:50201")
                        );
                        let mut payload = [0; 4];
                        stream.read_exact(&mut payload).await.unwrap();
                        stream.write_all(&payload).await.unwrap();
                    });
                }
                if slot != 0 {
                    tokio::spawn(async move {
                        let mut payload = vec![0; 65536];
                        let (n, peer, target, _) = udp.recv_from(&mut payload).await.unwrap();
                        assert_eq!(target.to_string(), format!("{target_ip}:50301"));
                        udp.send_to(peer, &target, &payload[..n]).await.unwrap();
                    });
                }
            }
            let json = std::ffi::CString::new(serde_json::to_vec(&nodes).unwrap()).unwrap();
            assert!(unsafe { vss_config(json.as_ptr()) });
            let (mut sip, mut sport) = (0u32, 0u16);
            assert!(unsafe { vss_udp_acquire(0x0a020201, &mut sip, &mut sport) }.is_null());
            for ip in [0x0a000050u32, 0x0a010146] {
                let client = TcpSocket::new_v4().unwrap();
                client.bind(loopback(0)).unwrap();
                let port = vss_open_tcp(ip, 50201, client.local_addr().unwrap().port());
                let mut stream = client.connect(loopback(port)).await.unwrap();
                stream.write_all(b"ping").await.unwrap();
                let mut result = [0; 4];
                stream.read_exact(&mut result).await.unwrap();
                assert_eq!(&result, b"ping");

                // Direct UDP: the same socket talks to the SS server with in-place crypto.
                let node = unsafe { vss_udp_acquire(ip, &mut sip, &mut sport) };
                assert!(!node.is_null());
                let socket = std::net::UdpSocket::bind(loopback(0)).unwrap();
                socket.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                let payload = [0x5a; 1200];
                let mut out = vec![0; 2048];
                let n = unsafe {
                    vss_udp_encrypt(node, ip, 50301, payload.as_ptr(), payload.len(), out.as_mut_ptr(), out.len())
                };
                assert!(n > 1200);
                let server = SocketAddr::from((Ipv4Addr::from(sip), sport));
                socket.send_to(&out[..n as usize], server).unwrap();
                let mut reply = vec![0; 2048];
                let got = tokio::task::spawn_blocking(move || {
                    let (len, from) = socket.recv_from(&mut reply).unwrap();
                    (reply, len, from)
                })
                .await
                .unwrap();
                assert_eq!(got.2, server);
                let mut reply = got.0;
                let (mut from_ip, mut from_port) = (0u32, 0u16);
                let len = unsafe { vss_udp_decrypt(node, reply.as_mut_ptr(), got.1, &mut from_ip, &mut from_port) };
                assert_eq!(len, 1200);
                assert_eq!((from_ip, from_port), (ip, 50301));
                assert_eq!(&reply[..1200], &payload[..]);
                reply[20] ^= 1;
                assert_eq!(unsafe { vss_udp_decrypt(node, reply.as_mut_ptr(), got.1, &mut from_ip, &mut from_port) }, -1);
                unsafe { vss_udp_release(node) };
            }
            assert!(unsafe { vss_config(std::ptr::null()) });
            assert_eq!(vss_open_tcp(0x0a000050, 50301, 44000), 0);
        })
        .await
        .unwrap();
    }

    // Server-speaks-first protocols (like VoiceFnConnection reading a 16-byte nonce before
    // writing anything) must receive the server banner without timing out.
    #[tokio::test(flavor = "multi_thread")]
    async fn server_speaks_first_over_tcp_relay() {
        let _guard = test_lock().lock().await;
        timeout(Duration::from_secs(10), async {
            let server = Context::new_shared(ServerType::Server);
            let mut nodes = Vec::new();
            let mut server_task = None;
            for slot in 0..3 {
                let key = format!("ssf-test-{slot}");
                let mut config =
                    ServerConfig::new(loopback(0), &key, CipherKind::AES_256_GCM).unwrap();
                let tcp = ProxyListener::bind(server.clone(), &config).await.unwrap();
                let addr = tcp.local_addr().unwrap();
                config.set_addr(addr);
                nodes.push(node_json(addr.port(), &key));
                if slot == 2 {
                    server_task = Some(tokio::spawn(async move {
                        let (mut stream, _) = tcp.accept().await.unwrap();
                        assert_eq!(stream.handshake().await.unwrap().to_string(), "10.1.1.10:55123");
                        // Immediately send 16-byte nonce BEFORE reading any client bytes!
                        let nonce = [0xab_u8; 16];
                        stream.write_all(&nonce).await.unwrap();
                        let mut reply = [0_u8; 4];
                        stream.read_exact(&mut reply).await.unwrap();
                        assert_eq!(&reply, b"ack!");
                        stream.write_all(&[1_u8]).await.unwrap();
                    }));
                }
            }
            let json = std::ffi::CString::new(serde_json::to_vec(&nodes).unwrap()).unwrap();
            assert!(unsafe { vss_config(json.as_ptr()) });

            let client = TcpSocket::new_v4().unwrap();
            client.bind(loopback(0)).unwrap();
            let port = vss_open_tcp(0x0a01010a, 55123, client.local_addr().unwrap().port());
            assert_ne!(port, 0);
            let mut stream = client.connect(loopback(port)).await.unwrap();
            // Read 16-byte nonce BEFORE writing a single byte!
            let mut got = [0_u8; 16];
            stream.read_exact(&mut got).await.unwrap();
            assert_eq!(got, [0xab_u8; 16]);
            stream.write_all(b"ack!").await.unwrap();
            let mut ack = [0_u8; 1];
            stream.read_exact(&mut ack).await.unwrap();
            assert_eq!(ack, [1_u8]);

            server_task.unwrap().await.unwrap();
            assert!(unsafe { vss_config(std::ptr::null()) });
        })
        .await
        .unwrap();
    }

    #[test]
    fn udp_decrypt_view_roundtrip_and_rejects_bad_packet() {
        let ctx = Context::new_shared(ServerType::Local);
        for method in [
            CipherKind::AES_128_GCM,
            CipherKind::AES_256_GCM,
            CipherKind::CHACHA20_POLY1305,
        ] {
            let ep = Endpoint {
                config: ServerConfig::new(loopback(1), "test-secret-key", method).unwrap(),
                tfo: false,
            };
            let flow = UdpFlow::new(UdpNode::new(&ep, ctx.clone()));
            let flow_ptr: *const UdpFlow = &flow;
            let payload: Vec<u8> = (0..1160).map(|i| (i * 31) as u8).collect();
            let mut wire = vec![0u8; payload.len() + flow.node.overhead()];
            let wire_len = unsafe {
                vss_udp_encrypt(
                    flow_ptr,
                    0x0a010114,
                    47998,
                    payload.as_ptr(),
                    payload.len(),
                    wire.as_mut_ptr(),
                    wire.len(),
                )
            };
            assert_eq!(wire_len as usize, wire.len());

            let (mut off, mut ip, mut port) = (0usize, 0u32, 0u16);
            let plain_len = unsafe {
                vss_udp_decrypt_view(
                    flow_ptr,
                    wire.as_mut_ptr(),
                    wire.len(),
                    &mut off,
                    &mut ip,
                    &mut port,
                )
            };
            assert_eq!(plain_len as usize, payload.len());
            assert_eq!(off, method.salt_len() + 7);
            assert_eq!((ip, port), (0x0a010114, 47998));
            assert_eq!(&wire[off..off + payload.len()], &payload[..]);

            // Tampered ciphertext must fail authentication.
            let mut tampered = vec![0u8; wire.len()];
            unsafe {
                vss_udp_encrypt(
                    flow_ptr,
                    0x0a010114,
                    47998,
                    payload.as_ptr(),
                    payload.len(),
                    tampered.as_mut_ptr(),
                    tampered.len(),
                );
            }
            tampered[method.salt_len() + 10] ^= 0x55;
            assert_eq!(
                unsafe {
                    vss_udp_decrypt_view(
                        flow_ptr,
                        tampered.as_mut_ptr(),
                        tampered.len(),
                        &mut off,
                        &mut ip,
                        &mut port,
                    )
                },
                -1
            );
        }
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn aead_2022_tcp_and_udp_roundtrip() {
        use shadowsocks::relay::udprelay::crypto_io::{decrypt_client_payload, encrypt_server_payload};
        let _guard = test_lock().lock().await;
        // Valid base64 PSKs: 16 bytes for aes-128-gcm, 32 bytes for aes-256-gcm / chacha20
        let cases = [
            ("2022-blake3-aes-128-gcm", CipherKind::AEAD2022_BLAKE3_AES_128_GCM, "AQIDBAUGBwgJCgsMDQ4PEA=="),
            ("2022-blake3-aes-256-gcm", CipherKind::AEAD2022_BLAKE3_AES_256_GCM, "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="),
            ("2022-blake3-chacha20-poly1305", CipherKind::AEAD2022_BLAKE3_CHACHA20_POLY1305, "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="),
        ];
        timeout(Duration::from_secs(15), async {
            for (cipher_name, method, psk) in cases {
                let server_ctx = Context::new_shared(ServerType::Server);
                let mut nodes = Vec::new();
                let mut server_task = None;
                let mut udp_key = Vec::new();
                for slot in 0..3 {
                    let mut config = ServerConfig::new(loopback(0), psk, method).unwrap();
                    let tcp = ProxyListener::bind(server_ctx.clone(), &config).await.unwrap();
                    let addr = tcp.local_addr().unwrap();
                    config.set_addr(addr);
                    if slot == 2 {
                        udp_key = config.key().to_vec();
                        server_task = Some(tokio::spawn(async move {
                            let (mut stream, _) = tcp.accept().await.unwrap();
                            assert_eq!(stream.handshake().await.unwrap().to_string(), "10.1.1.20:47984");
                            // Verify server-speaks-first works over AEAD-2022 TCP as well
                            stream.write_all(&[0x5a_u8; 16]).await.unwrap();
                            let mut req = [0_u8; 4];
                            stream.read_exact(&mut req).await.unwrap();
                            assert_eq!(&req, b"ping");
                            stream.write_all(b"pong").await.unwrap();
                        }));
                    }
                    nodes.push(serde_json::json!({
                        "server": "127.0.0.1",
                        "port": addr.port(),
                        "cipher": cipher_name,
                        "password": psk,
                    }));
                }
                let json = std::ffi::CString::new(serde_json::to_vec(&nodes).unwrap()).unwrap();
                assert!(unsafe { vss_config(json.as_ptr()) }, "vss_config failed for {cipher_name}");

                // 1. TCP relay E2E over AEAD-2022
                let client = TcpSocket::new_v4().unwrap();
                client.bind(loopback(0)).unwrap();
                let port = vss_open_tcp(0x0a010114, 47984, client.local_addr().unwrap().port());
                assert_ne!(port, 0);
                let mut stream = client.connect(loopback(port)).await.unwrap();
                let mut banner = [0_u8; 16];
                stream.read_exact(&mut banner).await.unwrap();
                assert_eq!(banner, [0x5a_u8; 16]);
                stream.write_all(b"ping").await.unwrap();
                let mut resp = [0_u8; 4];
                stream.read_exact(&mut resp).await.unwrap();
                assert_eq!(&resp, b"pong");
                server_task.unwrap().await.unwrap();

                // 2. UDP client -> server -> client E2E over AEAD-2022 (multiple packets + 0-byte packet)
                let (mut sip, mut sport) = (0u32, 0u16);
                let flow = unsafe { vss_udp_acquire(0x0a010114, &mut sip, &mut sport) };
                assert!(!flow.is_null());
                let overhead = unsafe { vss_udp_overhead(flow) };

                for (pkt_idx, payload_len) in [0usize, 1160, 1160].into_iter().enumerate() {
                    let payload: Vec<u8> = (0..payload_len).map(|i| ((i + pkt_idx * 17) & 0xff) as u8).collect();
                    let cap = (if payload_len == 0 { 900 } else { payload_len }) + overhead;
                    let mut wire = vec![0u8; cap];
                    let n = unsafe {
                        vss_udp_encrypt(
                            flow,
                            0x0a010114,
                            47998,
                            payload.as_ptr(),
                            payload.len(),
                            wire.as_mut_ptr(),
                            wire.len(),
                        )
                    };
                    assert!(n > 0, "encrypt failed for {cipher_name} len={payload_len}");
                    wire.truncate(n as usize);

                    // Server decrypts client packet with official shadowsocks SIP022 decoder
                    let (plain_len, dst_addr, ctrl) =
                        decrypt_client_payload(&server_ctx, method, &udp_key, &mut wire, None)
                            .expect("server decrypt_client_payload failed");
                    assert_eq!(plain_len, payload.len());
                    assert_eq!(&wire[..plain_len], &payload[..]);
                    assert_eq!(dst_addr.to_string(), "10.1.1.20:47998");
                    let client_ctrl = ctrl.expect("missing AEAD-2022 control data");
                    assert_eq!(client_ctrl.packet_id, pkt_idx as u64);

                    // Server encrypts reply with official shadowsocks SIP022 encoder
                    // Rotate server_session_id on pkt_idx == 2 to verify inline cache refresh.
                    let mut reply_ctrl = UdpSocketControlData::default();
                    reply_ctrl.server_session_id =
                        if pkt_idx == 2 { 0x8877665544332211 } else { 0x1122334455667788 };
                    reply_ctrl.client_session_id = client_ctrl.client_session_id;
                    reply_ctrl.packet_id = pkt_idx as u64;
                    let mut reply_wire = BytesMut::with_capacity(2048);
                    encrypt_server_payload(
                        &server_ctx,
                        method,
                        &udp_key,
                        &dst_addr,
                        &reply_ctrl,
                        &payload,
                        &mut reply_wire,
                    );

                    // Client decrypts reply via vss_udp_decrypt_view
                    let (mut off, mut from_ip, mut from_port) = (0usize, 0u32, 0u16);
                    let got = unsafe {
                        vss_udp_decrypt_view(
                            flow,
                            reply_wire.as_mut_ptr(),
                            reply_wire.len(),
                            &mut off,
                            &mut from_ip,
                            &mut from_port,
                        )
                    };
                    assert_eq!(got as usize, payload.len());
                    if matches!(
                        method,
                        CipherKind::AEAD2022_BLAKE3_AES_128_GCM
                            | CipherKind::AEAD2022_BLAKE3_AES_256_GCM
                    ) {
                        // Zero-copy fast path returns direct offset into reply_wire (>= 42 B).
                        assert!(off >= 42, "expected zero-copy offset >= 42 for {cipher_name}, got {off}");
                    } else {
                        assert_eq!(off, 0);
                    }
                    assert_eq!((from_ip, from_port), (0x0a010114, 47998));
                    assert_eq!(&reply_wire[off..off + payload.len()], &payload[..]);

                    // Wrong client_session_id must be rejected
                    reply_ctrl.client_session_id ^= 1;
                    let mut bad_reply = BytesMut::with_capacity(2048);
                    encrypt_server_payload(
                        &server_ctx,
                        method,
                        &udp_key,
                        &dst_addr,
                        &reply_ctrl,
                        &payload,
                        &mut bad_reply,
                    );
                    assert_eq!(
                        unsafe {
                            vss_udp_decrypt_view(
                                flow,
                                bad_reply.as_mut_ptr(),
                                bad_reply.len(),
                                &mut off,
                                &mut from_ip,
                                &mut from_port,
                            )
                        },
                        -1
                    );
                }

                unsafe { vss_udp_release(flow) };
                assert!(unsafe { vss_config(std::ptr::null()) });
            }
        })
        .await
        .unwrap();
    }
}
