// UDP media is encrypted/decrypted in place on Moonlight's own sockets (see
// ss_routes.c); only the few setup TCP connections use the loopback relay below.
use serde::Deserialize;
use shadowsocks::{
    config::{ServerAddr, ServerType},
    context::{Context, SharedContext},
    crypto::{
        v1::{random_iv_or_salt, Cipher},
        CipherKind,
    },
    relay::socks5::Address,
    ProxyClientStream, ServerConfig,
};
use std::{
    ffi::CStr,
    net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, ToSocketAddrs},
    os::raw::c_char,
    slice,
    sync::{Arc, Mutex, OnceLock},
    time::Duration,
};
use tokio::{io::copy_bidirectional, net::TcpListener, runtime::Runtime, time::timeout};

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
                "aes-128-gcm" | "aes-256-gcm" | "chacha20-ietf-poly1305"
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

/// A UDP node shared with C. Every socket using it holds one strong reference.
pub struct UdpNode {
    method: CipherKind,
    key: Box<[u8]>,
    host: ServerAddr,
    resolved: Mutex<Option<SocketAddrV4>>,
}
impl UdpNode {
    fn new(endpoint: &Endpoint) -> Arc<Self> {
        let config = &endpoint.config;
        Arc::new(Self {
            method: config.method(),
            key: config.key().into(),
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
        self.method.salt_len() + 7 + self.method.tag_len()
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
        Some(Routes {
            home_udp_node: UdpNode::new(&home_udp),
            company_udp_node: UdpNode::new(&company),
            home_tcp,
            company,
            ctx: Context::new_shared(ServerType::Local),
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

/// Selects the UDP node for an IPv4 destination (host order) and resolves its server.
/// Returns a strong reference to release with `vss_udp_release`, or null.
#[no_mangle]
pub unsafe extern "C" fn vss_udp_acquire(ip: u32, server_ip: *mut u32, server_port: *mut u16) -> *const UdpNode {
    let Some(routes) = current() else { return std::ptr::null() };
    let node = match ip >> 8 {
        0x0a0000 => &routes.home_udp_node,
        0x0a0101 => &routes.company_udp_node,
        _ => return std::ptr::null(),
    };
    let Some(server) = node.server() else { return std::ptr::null() };
    *server_ip = u32::from(*server.ip());
    *server_port = server.port();
    Arc::into_raw(node.clone())
}

#[no_mangle]
pub unsafe extern "C" fn vss_udp_release(node: *const UdpNode) {
    if !node.is_null() {
        drop(Arc::from_raw(node));
    }
}

#[no_mangle]
pub unsafe extern "C" fn vss_udp_overhead(node: *const UdpNode) -> usize {
    (*node).overhead()
}

/// Writes `salt | AEAD(ATYP_V4 ip port | payload) | tag` to `out`.
#[no_mangle]
pub unsafe extern "C" fn vss_udp_encrypt(
    node: *const UdpNode,
    ip: u32,
    port: u16,
    data: *const u8,
    len: usize,
    out: *mut u8,
    cap: usize,
) -> isize {
    let node = &*node;
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

/// Decrypts a datagram in place and moves its payload to the start of `buf`.
/// Returns the payload length and the original IPv4 sender, or -1 to drop it.
#[no_mangle]
pub unsafe extern "C" fn vss_udp_decrypt(
    node: *const UdpNode,
    buf: *mut u8,
    len: usize,
    ip: *mut u32,
    port: *mut u16,
) -> isize {
    let node = &*node;
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
    let payload = plain.len() - 7;
    let start = salt_len + 7;
    packet.copy_within(start..start + payload, 0);
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

    // Distinct listeners and keys make swapping either home subscription observable.
    #[tokio::test(flavor = "multi_thread")]
    async fn split_routes_preserve_destinations_and_return_traffic() {
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
}
