这一版最严重的问题不是内容本身，而是修订记录声称已改的条目，正文里多数并没有真正落进去。逐条核对后，列出对不上的地方。

一、修订记录与正文不一致（5 处）
1. U1 单测挂 Advisor：修订记录说改了 §8.0，正文没改
   修订记录写：

单测构建 ChatClient 时显式挂 MessageChatMemoryAdvisor（真实内存 ChatMemory 实例），与生产装配一致；U1 直接断言 chatMemory.get()

但 §8.0 实际说明只有：

ChatClient 链式 API 不可直接 Mock：单元测试先 Mock ChatModel.call(Prompt) 返回固定内容，再围绕该 Mock 构建真实 ChatClient 注入被测服务

没有一句提到挂 Advisor。而 §8.1 U1 的断言仍是：

记忆新增 user（原始问题）+ assistant

按 §8.0 现在描述的方式构建的 ChatClient 不带 Advisor，U1 这句断言在单测里必然失败。这是上一轮的核心问题，实际没修。

2. I2 Mock 验证方式：修订记录说改了 §8.0，正文没改
   修订记录写：

§8.0 说明补充：实施时第一步验证 @MockitoBean 覆盖后 ChatClient.Builder 是否绑定 Mock，未绑定则用 @TestConfiguration 显式覆盖 ChatClient Bean

但 §8.0 实际说明里没有任何这类表述。这是上一轮的第 4 条，也没修。

3. U15 并发验证方法：修订记录说改了 §8.1，正文仍是旧文案
   修订记录写：

改为 AtomicInteger 并发峰值法（call 进入计数 + 100ms 睡眠，断言 maxConcurrent==1），弃用总耗时断言

但 §8.1 U15 的实际文字仍是：

两线程同时 ask 同一 sessionId，验证互斥（锁计数器/总耗时单调）

旧文案原封不动。这是上一轮第 5 条，同样没修。

4. §3 兜底 B 措辞：修订记录说改了，正文仍是“防止膨胀”
   修订记录写：

§3“防止膨胀”改“保持对话连贯”

但 §3 流程图里实际仍是：

+ 路径②写入（防止历史继续膨胀越走越窄）

上一轮第 6 条，没改。§6.3 兜底表里这一处倒是改成了“保持对话连贯并引导用户清空会话”，说明确实知道要改，但 §3 漏了。

5. §8.4 excludes：修订记录说补了，配置里没有
   修订记录写：

§8.4 配置补 excludes

但 §8.4 的 JaCoCo <configuration> 里只有：

xml
<includes>
<include>com.wuyunbin.rag.**</include>
</includes>
没有 <excludes>。同时修订记录说“MilvusRestStore 明确纳入 JaCoCo excludes”，配置示例里没有任何体现，正文的“原则”一节也只提了“仅允许排除 RagApplication 启动类”。

这条影响实际验收：mvn verify 跑覆盖率时，MilvusRestStore（206 行，占主代码 36%）会算进分母，但单测按 §8.0 的设计又不覆盖它（HTTP 细节由切片 B 联调覆盖），30% 能不能过完全取决于其他类的实现行数。这需要把 excludes 真正写进配置示例，否则实施时要么漏改，要么踩坑。

二、新发现的小问题（4 处）
6. §8.3 日志的 fallback 枚举缺少话术 C 的分支
   结构化日志里写：

text
fallback=NONE|NO_HIT|BUDGET_EXHAUSTED
话术 C（空回复）是新引入的场景，枚举里没有对应值。建议加 EMPTY_REPLY，否则日志无法区分“正常返回但内容空白”和“走了兜底 A/B”。

7. §3 流程图“成功但 reply 空白”分支未指明话术 C
   §3 流程图里写：

成功但 reply 空白 → 不移除：历史按 gate 实测行为保留（§6.5），客户端收到兜底话术

这里“兜底话术”指什么不明确。§6.3 表格里明确是话术 C，但流程图里没跟着更新。

8. §8.3 用例 ④d 的断言仍写“兜底话术”，应明确为话术 C
   断言（依 gate ② 结论）：客户端收到兜底话术；记忆中无孤立 user（有 A_empty 或有话术）；下一轮追问正常

按 §6.3，这里是话术 C。测试写断言时，应该明确是 C 而不是 A/B。

9. §4.3 未注明未知 sessionId 不报错
   修订记录写：

§4.3/§4.4 注明未知 sessionId 不报错

§4.4 确实加了：

不存在的 sessionId 幂等返回 200，不报错（与 §8.2 I8 对齐）

但 §4.3 没有加。建议在 §4.3 的描述末尾补一句“未知 sessionId 返回空数组（不 404）”，与 §8.2 I8 对齐。

三、结论
这一版不是技术方案有问题，而是上一轮评审的修订没有真正落到正文。修订记录本身写得像做完了，但正文仍是 v7 的旧文案，两者不一致。

建议：不需要重新评审技术方案，只做一次“修订记录 vs 正文”的对账，把上面 5 处不一致逐条修掉：

§8.0 补 U1 单测挂 Advisor 的说明；

§8.0 补 I2 的 Mock 验证策略；

§8.1 U15 改成 AtomicInteger 并发峰值法；

§3 兜底 B 措辞改为“保持对话连贯”；

§8.4 配置补 excludes（MilvusRestStore 至少）。

同时顺手把 §8.3 日志加 EMPTY_REPLY、§3 流程图和 §8.3 ④d 明确“话术 C”、§4.3 补“未知 sessionId 返回空数组”，就可以进 §9 第 0 步 gate 了。