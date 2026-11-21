# EXT-SS-NETWORK · 内置 SS 组网

导入组网配置后，访问 10.0.0.0/24（家庭）和 10.1.1.0/24（公司）的流量会经 Shadowsocks 节点转发。家庭 TCP、家庭 UDP 各来自一份 Clash 订阅，公司节点直接写在配置里。

串流 UDP（视频、音频、ENet 控制）不经过本地中继：运行时改写 `libmoonlight-core.so` 的导入表（GOT），让 Moonlight 自己的 socket 直接发往 SS 节点，并在收包线程里原地解密，路径为 5G → SS 解密 → Moonlight 接收器。配对 HTTP、RTSP、地址探测和语音 Fn 等 TCP 连接走一次性的本地回环中继；麦克风上行的 Java UDP 由 `SsDatagramSocket` 加解密。native 层的 USB/IP TCP 也会被钩子自动转发。

主页顶栏在手柄按钮后插入一个圆形按钮：灰色表示关闭，绿色表示已连接且节点可达，琥珀色表示有节点不可达。家庭 UDP 订阅无法用 TCP 探测，显示“仅 UDP”，不计入状态。只打包 ARM 两个 ABI，x86 设备上按钮显示“不支持”。

## 功能归属

除 `dev/extensions/EXT-SS-NETWORK.md` 外，所有文件都在 `app/src/ext-ss-network/`，由 `ss.gradle` 挂到 main 源集上。

| 位置（相对 app/src/ext-ss-network） | 职责 |
|---|---|
| ss.gradle、proguard-ss.pro | 源集、jniLibs、Rust 构建任务、snakeyaml 依赖与混淆规则。 |
| rust/ | `libss_network.so`：SS AEAD 加解密、节点选择、TCP 回环中继。`build-android.py` 用工程的 NDK 交叉编译，并按需执行 `rustup target add`。 |
| jni/ss_hook.c | `libss_network_jni.so`：GOT 钩子（connect/sendto/sendmsg/recvfrom/recvmsg/__recvfrom_chk/close）、UDP 直连、JNI。 |
| java/…/SsApplication.java | 进程入口：恢复状态，通过 Activity 生命周期回调给 PcView 注入按钮。 |
| java/…/SsRoutes.java | 配置导入、订阅解析、持久化、节点探测、状态，以及给 NvConnection 用的 `tuneStream`。 |
| java/…/SsSocketFactory.java、SsDatagramSocket.java | Java 层 TCP / UDP 接入。 |
| java/…/SsToolbar.java、SsStatusIcon.java、SsPanel.java、SsImportFragment.java、SsMaxHeightScrollView.java | 顶栏按钮、状态图标、控制面板（沿用 AppDialog 与 home 配色）、配置选择回调。 |
| res/ | 面板布局、样式、按钮背景、中英文文案（全部以 `ss_` 开头）。 |

## 合并上游时的接入点

上游文件内的接入都用 `EXT-SS-NETWORK` 标记，可用 `rg -n 'EXT-SS-NETWORK.*MODIFIED' app` 列出。

| 上游文件 | 合并时核对 |
|---|---|
| app/build.gradle | 末尾 `apply from: "src/ext-ss-network/ss.gradle"`。 |
| AndroidManifest.xml | `<application android:name=".extensions.ss.SsApplication">`。若上游自己加了 Application，把 `SsApplication` 里的初始化和生命周期回调搬过去即可。 |
| nvstream/NvConnection.java | `resolveServerAddress` 的探测 socket 改用 `SsSocketFactory`；确定远程/局域网参数后调用 `SsRoutes.tuneStream`，走 SS 的主机强制远程模式、包长 1024。 |
| nvstream/http/NvHTTP.java | OkHttp `socketFactory(SsSocketFactory.INSTANCE)`。 |
| nvstream/VoiceFnConnection.java | Fn socket 由 `SsSocketFactory` 创建。 |
| nvstream/MicUplinkConnection.java | 上行 socket 由 `SsDatagramSocket.create()` 创建。 |

另外两项依赖上游结构，但不在上游文件里：
- 主页按钮靠 `R.id.axiButton` 所在的 `LinearLayout` 定位。若上游改了顶栏，按钮只是不出现，不会崩溃。
- GOT 钩子要求上述 7 个函数仍是 `libmoonlight-core.so` 的导入符号，可用 `llvm-readelf -r libmoonlight-core.so` 核对。

## 构建与验证

需要 python3 和 Rust（cargo）。合并后运行 `./gradlew :app:assembleNonRootDebug`，并在设备上检查：
- 未配置时导入配置，开关组网，按钮颜色在灰、绿、琥珀之间切换；
- 横竖屏下按钮位置正确，旋转后仍在；
- 经 SS 配对、串流、麦克风上行和语音 Fn 都正常；
- 关闭组网后局域网串流不受影响。
