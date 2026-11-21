# EXT-REMOTE-VOICE · StarCore 远程语音

手机在串流内点按语音按钮开始／结束听写，长按展开 Esc、Tab、退格和回车。回车短按发送 Enter，长按发送一次 Shift+Enter。语音开启时，点串流画面先结束 Fn，收到主机确认后再转发原点击。构建 23 的 StarCore Pro 支持结束回执。

## 功能归属

| 位置（相对 app/src/main） | 职责 |
|---|---|
| java/com/limelight/extensions/voice/VoiceInputExtension.java | Activity 与功能的唯一 UI 接口，绑定串流、权限入口、状态、快捷键与鼠标事件。扩展自行读取默认开启的偏好。 |
| 同目录的 RemoteVoiceInput、VoiceScreenTouch | 串行启动／取消／停止、等待 Fn 回执、保留点击次序；拖动、多指及超时触摸回到原串流输入。 |
| 同目录的 VoiceButtonTouch、VoiceKeyFan、VoiceKeyMenu | 拖动吸边、保存位置、边角布局、菜单与快捷键手势。 |
| res/layout/voice_input_overlay.xml、res/drawable/bg_voice_input.xml 和 ic_voice_*.xml | 悬浮层及外观。 |
| java/com/limelight/nvstream/VoiceGestureSession.java、VoiceFnConnection.java | 音频会话所有权、Fn 握手、连接断开及结束确认。会话操作共用 NvConnection 的锁，提前手动开启的麦克风由原入口关闭。 |
| app/src/test/java/com/limelight/extensions/voice/（仓库根下） | 状态机、快速点击、停止失败、延迟输入、菜单排布与碰撞测试。 |

## 合并上游时的接入点

从此次合入前的基线 `feb975a3` 起，上游文件内的接入都以 `EXT-REMOTE-VOICE` 标记。可用 `rg -l 'EXT-REMOTE-VOICE.*MODIFIED' app/src/main` 列出这些文件。

| 上游文件 | 合并时核对 |
|---|---|
| Game.java | 串流创建后绑定一次；连接、隐藏覆盖层、暂停、失焦、配置变化、切后台与断线触发相应可见性或取消。普通触摸、缩放层点击和虚拟鼠标按下／抬起都保留结束确认的顺序。麦克风授权、手动开关与状态展示共用原入口。 |
| NvConnection.java | 在同一把锁内进入／结束 Fn 手势；关闭串流后禁止迟到的启动；区分手动上行与 Fn 创建的上行。 |
| ConnectionContext.java、http/NvHTTP.java | 复用原配对 HTTPS 的 /mic-uplink 协商，另读可选 axiFnPort、axiFnKey。缺少 Fn 字段时普通手动上行仍可用。 |
| MicUplinkConnection.java | 保留已有 start() 入口；语音启动支持取消，在打开录音前和发包前检查取消状态，停止时安全释放采集与网络资源。 |
| layout/activity_game.xml、xml/preferences.xml、preferences/SettingsCatalog.java | 保留单个悬浮布局引用及声音设置入口，沿用 checkbox_voice_input_enabled 和原位置偏好键，便于覆盖升级。 |

合并后从仓库根运行 `./gradlew :app:testNonRootDebugUnitTest :app:assembleNonRootDebug`。在设备上检查开关关闭时原串流触摸和手动麦克风、开启时点按听写与点屏结束、断流后重新连接、两侧和四角拖动、长按回车后松手只换行一次。Fn 通信测试可搭配 StarCore Pro 构建 23 的 mac/tests/VoiceFnIntegration.java 与 keys.m 在回环运行。
