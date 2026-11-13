# 工作模式协议：100% 对齐与远端驱动规程 (MODE-ALIGN-100)

更新时间：2026-11-14。

为彻底根除本地 AI (WorkBuddy) 自主发挥、字面敷衍导致「与网页呈现效果差距巨大」的问题，本项目后续所有 UI 演进与特性开发永久执行本规程。

---

## 核心原则：100% 对齐，零自主改动

1. **助手远端全权实现**：
   - 所有 UI 组件的几何运算（如非对称圆角、Padding、Gap）、手势拦截（Grab Offset、绝对位移）、动态微动效（如 unfold_more 图标淡显、贝塞尔曲线气泡）必须由助手在远端依据经检验的网页原型（如 v4/v6）或官方/Rhythm 源码完成 100% 细节编写。
   - 严禁助手只给笼统建议或抽象伪代码。

2. **WorkBuddy 零自主动作**：
   - 本地 AI (WorkBuddy) 仅作为纯粹的「拉取、编译、安装、观察反馈」执行器。
   - **绝对禁止** WorkBuddy 在本地自行决定 UI 尺寸、增删布局层级、篡改间距或改动动效时长。
   - WorkBuddy 仅执行机械命令：
     ```bash
     git fetch origin <target-branch>
     git checkout <target-branch>
     ./gradlew assembleDebug
     adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
     ```

3. **双重阻断与授权确认**：
   - 助手提交任何代码至 GitHub 独立分支前，必须通过 `confirm_action` 获得用户授权，绝不静默改写。
   - 任何改动均在独立分支进行，严禁触碰 `main` 分支。
