主干由两个插件组成：
- superpowers —— 思考与流程层（plan/brainstorm/debug/TDD/review/verify）
- gstack —— 执行与外部世界层（browser/QA/ship/deploy/canary/护栏）

类比：superpowers 是大脑，gstack 是手脚。

## 核心原则

1. 流程归 superpowers：所有 plan、brainstorm、debug、TDD、verify、
   code review 默认走 superpowers。
2. 执行归 gstack：所有浏览器操作、QA 测试、ship、deploy、canary、
   retro 走 gstack。
3. 独立 reviewer 通道：作者和审查者绝不在同一上下文里互评。
4. 证据优先：声明完成前必须收集可验证的证据。
5. 遇到歧义先 brainstorm。

## 浏览器规则

/browse 是唯一的浏览器入口。禁止使用 mcp__claude-in-chrome__*
和 mcp__computer-use__* 来操作浏览器。

## 不要重复造轮子

下列能力只走 superpowers：
- plan / brainstorm / writing-plans / executing-plans
- TDD / debugging / verification
- code review（请求和接收）
- subagent / parallel dispatch
- worktrees

下列能力只走 gstack：
- 浏览器、QA、ship、deploy、canary、retro、护栏