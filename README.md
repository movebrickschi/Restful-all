<div align="center">

# 🔍 Restful-all

**跨语言跨框架的 REST 路由搜索器 · IntelliJ IDEA 插件**

*A multi-language, multi-framework REST route navigator for IntelliJ IDEA*

[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2025.2%2B-000000?logo=intellijidea&logoColor=white)](https://plugins.jetbrains.com/)
[![JDK](https://img.shields.io/badge/JDK-21%2B-orange?logo=openjdk&logoColor=white)](https://openjdk.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.0%2B-02303A?logo=gradle&logoColor=white)](https://gradle.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[🚀 快速开始](#使用方式) · [✨ 功能特性](#功能特性) · [📐 项目结构](#项目结构) · [💬 反馈](https://github.com/movebrickschi/Restful-all/issues)

</div>

---

## 💡 为什么写这个插件

在大型项目中，快速定位「某个 URL 到底在哪个控制器里」是个高频但费时的需求。IDE 自带的 Search Everywhere 在跨框架、跨语言项目里不够好用，于是有了这个插件。

一句话总结：**`Ctrl+Alt+/` 一键唤起全项目 REST 路由列表，输关键字快速跳转。**

什么人会需要：

- 同时维护 **Java 后端 + Vue/React 前端** 的全栈开发者
- 需要频繁在 **NestJS / Spring / Express / FastAPI** 多个项目间切换的架构师
- 接手老项目、需要快速梳理 API 全貌的开发者

---

## Restful-all

一款 IntelliJ IDEA 插件，用于自动发现项目中的 REST API 路由，并通过快捷键弹框快速搜索跳转到对应路由位置。

## 功能特性

- **多框架支持**：自动识别 NestJS、Spring Boot、Express.js、FastAPI、Flask 等主流 REST 框架的路由定义
- **快捷键搜索**：`Ctrl+Alt+/`（Mac: `Cmd+Alt+/`）一键唤起路由搜索弹框
- **选中文本搜索**：在编辑器中选中路由路径后按快捷键，自动将选中内容填入搜索框进行搜索
- **实时过滤**：支持按路由路径、类名、方法名、HTTP Method 进行子字符串模糊匹配，150ms 防抖优化
- **一键跳转**：选中路由后 Enter 或双击即可跳转到源码对应位置
- **彩色标识**：不同 HTTP Method（GET/POST/PUT/DELETE 等）以不同颜色高亮显示
- **智能扫描**：自动跳过 `node_modules`、`dist`、`build` 等无关目录，跳过超过 512KB 的大文件
- **Monorepo 支持**：当 ProjectFileIndex 无法遍历文件时，自动回退到 VFS 递归扫描

## 支持的框架与路由模式

### NestJS (TypeScript)

```typescript
@Controller('v1/action')
export class ActionController {
  @Get('/result')
  async getActionResult() { ... }

  @Post('/abort')
  async abortAction() { ... }
}
```

识别的装饰器：`@Controller`、`@Get`、`@Post`、`@Put`、`@Delete`、`@Patch`、`@Head`、`@Options`、`@All`

### Spring Boot (Java / Kotlin)

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    @GetMapping("/list")
    public List<User> listUsers() { ... }

    @PostMapping("/create")
    public User createUser(@RequestBody UserDTO dto) { ... }
}
```

识别的注解：`@RestController`、`@Controller`、`@RequestMapping`、`@GetMapping`、`@PostMapping`、`@PutMapping`、`@DeleteMapping`、`@PatchMapping`

### Express.js (JavaScript / TypeScript)

```javascript
router.get('/users', listUsers);
app.post('/users/create', createUser);
```

识别模式：`router.<method>('/path', ...)` 和 `app.<method>('/path', ...)`

### FastAPI (Python)

```python
@app.get("/items/{item_id}")
async def read_item(item_id: int):
    ...
```

### Flask (Python)

```python
@app.route("/items", methods=["GET"])
def list_items():
    ...
```

## 使用方式

### 快捷键

| 快捷键 | 功能 |
|--------|------|
| `Ctrl+Alt+/`（Mac: `Cmd+Alt+/`） | 打开路由搜索弹框 |

也可以通过菜单栏 **Navigate → 搜索 REST 路由** 访问。

### 选中文本搜索

在编辑器中选中一段文本（如路由路径 `/v1/user/settings`），然后按 `Ctrl+Alt+/`，搜索弹框会自动将选中内容填入搜索框并过滤结果。未选中文本时正常打开空搜索框。

### 搜索弹框操作

| 操作 | 说明 |
|------|------|
| 输入文字 | 实时过滤路由列表（150ms 防抖） |
| `↑` / `↓` | 上下选择路由 |
| `Enter` | 跳转到选中路由的源码位置 |
| 双击 | 跳转到对应路由 |
| `Esc` | 关闭弹框 |

> 当匹配结果超过 200 条时，列表仅显示前 200 条，状态栏会提示总匹配数。

### 搜索弹框显示格式

每条路由显示以下信息：

```
GET     /v1/action/result    ActionController#getActionResult  action.controller.ts:12  [NestJS]
POST    /v1/action/abort     ActionController#abortAction      action.controller.ts:22  [NestJS]
```

- **HTTP Method**：彩色标识（GET 蓝色、POST 绿色、PUT 黄色、DELETE 红色、PATCH 紫色）
- **路由路径**：完整的 API 路径
- **类名#方法名**：路由所在的类和方法
- **文件位置**：文件名和行号
- **框架标识**：所属框架（NestJS / Spring / Express / Python）

## 项目结构

```
src/main/kotlin/io/github/movebrickschi/restfulall/
├── model/
│   └── RouteInfo.kt              # 路由数据模型、HttpMethod 枚举、Framework 枚举
├── scanner/
│   ├── RouteScanner.kt           # 扫描器接口
│   ├── NestJsRouteScanner.kt     # NestJS 装饰器路由扫描
│   ├── SpringRouteScanner.kt     # Spring Boot 注解路由扫描
│   ├── ExpressRouteScanner.kt    # Express.js 路由扫描
│   └── PythonRouteScanner.kt     # FastAPI / Flask 路由扫描
├── service/
│   └── RouteService.kt           # 项目级服务：路由扫描调度与缓存管理
├── action/
│   └── SearchRouteAction.kt      # AnAction：快捷键触发的入口
├── ui/
│   └── RouteSearchPopup.kt       # 搜索弹框 UI：过滤、列表、导航
├── MyToolWindow.kt               # Compose 工具窗口（模板自带）
└── MyMessageBundle.kt            # 国际化资源绑定
```

### 核心模块说明

| 模块 | 说明 |
|------|------|
| `RouteInfo` | 路由信息数据类，包含 HTTP 方法、完整路径、类名、方法名、文件引用、行号、所属框架；预计算 `displayPath` 和 `searchKey` 避免搜索时重复分配 |
| `RouteScanner` | 扫描器接口，定义 `supportedExtensions()` 和 `scanFile()` 两个方法 |
| `NestJsRouteScanner` | 通过正则匹配 `@Controller` + `@Get/@Post/...` 装饰器模式 |
| `SpringRouteScanner` | 通过正则匹配 `@RequestMapping` + `@GetMapping/...` 注解模式 |
| `ExpressRouteScanner` | 通过正则匹配 `router.get()`、`app.post()` 等调用模式 |
| `PythonRouteScanner` | 通过正则匹配 FastAPI `@app.get()` 和 Flask `@app.route()` 模式 |
| `RouteService` | 项目级服务，遍历项目文件并调用各扫描器，结果去重排序后缓存；支持 VFS 回退扫描，限制文件大小上限 512KB |
| `SearchRouteAction` | 后台执行路由扫描，获取编辑器选中文本，完成后在 EDT 线程弹出搜索框 |
| `RouteSearchPopup` | 基于 `JBPopupFactory` 构建的搜索弹框，支持选中文本预填充、防抖过滤、列表上限 200 条 |

## 构建与运行

### 环境要求

- JDK 21+
- Gradle 9.0+
- IntelliJ IDEA 2025.2+

### 构建插件

```bash
./gradlew buildPlugin
```

构建产物位于 `build/distributions/` 目录。

### 本地调试

使用预置的 Run Configuration **Run IDE with Plugin**，或执行：

```bash
./gradlew runIde
```

将启动一个带有本插件的 IntelliJ IDEA 沙箱实例。

### 安装插件

1. 打开 IntelliJ IDEA → **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk...**
2. 选择 `build/distributions/Restful-all-*.zip`
3. 重启 IDE

## 扩展开发

如需支持新的 REST 框架，只需：

1. 实现 `RouteScanner` 接口
2. 在 `RouteService` 的 `scanners` 列表中注册新的扫描器实例

```kotlin
class MyFrameworkScanner : RouteScanner {
    override fun supportedExtensions() = setOf("rb") // 例如 Ruby

    override fun scanFile(file: VirtualFile): List<RouteInfo> {
        // 实现路由扫描逻辑
    }
}
```

## 配置

| 配置项 | 值 |
|--------|-----|
| Plugin ID | `io.github.movebrickschi.Restful-all` |
| 最低 IDE 版本 | 2025.2 (Build 252.25557) |
| 快捷键 | `Ctrl+Alt+/` (默认 Keymap) |
| 菜单位置 | Navigate → 搜索 REST 路由 |

## License

[MIT](LICENSE) © [movebrickschi](https://github.com/movebrickschi)

---

<div align="center">

**如果这个插件提高了你的效率，请点个 Star ⭐**<br/>
*If this plugin makes your day easier, please leave a star ⭐*

[⚡ 查看作者其他项目 / See more works](https://github.com/movebrickschi)

</div>
