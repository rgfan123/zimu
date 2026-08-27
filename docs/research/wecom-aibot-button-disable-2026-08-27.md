# 企微智能机器人(aibot)卡片按钮置灰调研

- 日期:2026-08-27
- 问题:用户点击 button_interaction 卡按钮后,能否让按钮「置灰/禁用」而不是整卡替换?
- 取证方式:官方文档页 curl 抓取 SSR HTML 逐字检索 + WebFetch 交叉验证 + 官方样式图直接查看。所有引文均为文档原文;推断项在 §8 单独标明。

---

## 0. 结论先行

1. **aibot 回调协议没有 `update_button`,也没有任何按钮级置灰/禁用字段。** 被动回复文档(101031)全文检索 `update_button` 0 次;`replace_name`、`replace_text` 在 aibot 全部文档(100719/101027/101031/101032/101138/101463)中均为 0 次。你记忆中的 `update_button`(XML,`Button.ReplaceName`)是**自建应用(应用消息)回调**的被动回复能力([90241](https://developer.work.weixin.qq.com/document/path/90241));`button.replace_name` 是应用消息 **update_template_card 云端 API**([94888](https://developer.work.weixin.qq.com/document/path/94888))的能力。两条通路 aibot 都用不了(见 §5)。
2. **aibot 对卡片点击事件唯一的卡片更新手段 = 被动回复 `response_type: update_template_card`,语义是整卡替换**,且必须在 5 秒内、仅一次机会([101031](https://developer.work.weixin.qq.com/document/path/101031)、[101027](https://developer.work.weixin.qq.com/document/path/101027))。长连接模式等价(`aibot_respond_update_msg`,body 同为 `update_template_card`,同样仅限卡片点击事件、5 秒时限,[101463](https://developer.work.weixin.qq.com/document/path/101463))。`response_url` 主动回复只能**另发一条新消息**(markdown / template_card,1 小时内一次),群聊场景会引用原卡而非更新原卡([101138](https://developer.work.weixin.qq.com/document/path/101138))。
3. **aibot 的 Button 结构只有 `text` / `style` / `key` 三个字段,无 disabled**([101032](https://developer.work.weixin.qq.com/document/path/101032))。aibot 文档里官方的 `disable` 布尔只存在于**下拉选择器(SelectionItem)**和**投票选择框(CheckBox)**,且原文明确「仅在更新模版卡片的时候该字段有效」——正是为更新置灰设计的,但按钮没有同款。
4. **工程上最接近「按钮变灰」的做法:整卡替换为一张新的 button_interaction 卡**——按钮文案改「已确认 ✓」、`style: 4`(灰底黑字中性样式,见 §4 样式图)、key 换 noop、服务端幂等吞掉重复点击。按钮视觉变灰但**仍可点击**(协议层无禁点能力)。button_interaction 的 `card_action` 是可选字段,这条路**不会触发 42045**。
5. **42045 的根因已锤实**:全局错误码表 42045 = 「Template_Card.card_action 缺失或不合法」([96213](https://developer.work.weixin.qq.com/document/path/96213));而 101032 原文写明 text_notice 卡「card_action …… **是**(必填)…… text_notice模版卡片中该字段为必填项」,且 CardAction「text_notice和news_notice模版卡片中该字段取值范围为[1,2]」。即:现状「点击后换 text_notice 卡」必然要带跳转型 card_action,整卡变成一个链接。改用方案 A(button_interaction 置灰样式卡)即可同时甩掉 42045 和「整卡变链接」两个副作用。

---

## 1. 三条产品线,别串

| 产品线 | 发送通道 | 卡片点击回调 | 按钮置灰能力 | 文档入口 |
|---|---|---|---|---|
| **智能机器人 aibot**(本系统) | 被动回复 / response_url 主动回复 | JSON,`msgtype: event` + `eventtype: template_card_event` | ❌ 无按钮级能力,只能整卡替换 | [101039 概述](https://developer.work.weixin.qq.com/document/path/101039) |
| 自建应用(应用消息) | `message/send` API | XML 事件推送 | ✅ 被动回复 `MsgType=update_button` + `Button.ReplaceName`;或调 [94888] API `button.replace_name` / `replace_text` | [90241 被动回复消息格式](https://developer.work.weixin.qq.com/document/path/90241) |
| 群机器人(现名「消息推送」) | webhook | 无按钮回调 | 不适用 | [91770 消息推送配置说明](https://developer.work.weixin.qq.com/document/path/91770) |

- 你提供的链接 [90372](https://developer.work.weixin.qq.com/document/path/90372) 实际渲染出来的页面标题是**「发送应用消息」**(`message/send` API,页面自带按钮样式图与 template_card 定义)——它属于应用消息线,**与 aibot 回调协议无关**,也不是群机器人文档。群机器人文档现挂在「消息推送(原"群机器人")」目录(91770 / 99110)。
- aibot 完整文档树(取自 [101468](https://developer.work.weixin.qq.com/document/path/101468) 侧边栏):概述 101039 / 接收消息 100719 / 接收事件 101027 / 被动回复消息 101031 / 模板卡片类型 101032 / 加解密 101033 / 主动回复消息 101138 / 长连接 101463。

## 2. aibot 回调响应权威清单

来源:[101031 被动回复消息](https://developer.work.weixin.qq.com/document/path/101031)(章节结构从页面 HTML 逐字提取)。

| 回调场景 | 可用响应 | 字段名 |
|---|---|---|
| 回复欢迎语(enter_chat) | `text`、`template_card` | `msgtype` |
| 回复用户消息 | `stream`、`template_card`、`stream_with_template_card` | `msgtype` |
| **模板卡片事件(按钮点击)** | **`update_template_card`(唯一)** | **`response_type`** |

- 原文:「当机器人服务接收到模版卡片事件后,可以在该事件的返回包中添加消息进行即时响应。」
- 全文无 `update_button`(字符串检索 0 次)。
- 时限原文([101027](https://developer.work.weixin.qq.com/document/path/101027)):「当有模版卡片回调事件的时候,**企业微信服务只会发起一次请求,企业微信服务器在五秒内收不到响应会断掉连接,丢弃该回调事件**。」
- 事件正名:官方事件叫 `template_card_event`(`msgtype: event`),携带 `card_type` / `event_key`(按钮 key)/ `task_id` / `selected_items`,外层有 `chattype`(single/group)、`response_url`。我们内部叫的「card_action 事件」建议在代码注释里对齐官方名。
- 兜底通道([101138 主动回复消息](https://developer.work.weixin.qq.com/document/path/101138)):`POST {response_url}`,「该 response_url 有效期为 1 个小时」「每个 response_url 用户可以调用接口一次」,body 仅支持 `msgtype: markdown | template_card`——是**发新消息**,不是更新;群聊中「主动回复消息的时候会引用触发回调的用户消息/被点击模板卡片消息」。
- 长连接模式([101463](https://developer.work.weixin.qq.com/document/path/101463)):`cmd: aibot_respond_update_msg`,body 即 §3 的 `update_template_card` 结构;「该命令仅适用于模板卡片点击事件」,同为 5 秒时限。回调模式与长连接模式二选一。

## 3. update_template_card 的准确结构与约束

来源:[101031](https://developer.work.weixin.qq.com/document/path/101031),JSON 与字段表为原文。

```json
{
    "response_type": "update_template_card",
    "userids": ["USERID1", "USERID2"],
    "template_card": {
        "feedback": { "id": "FEEDBACKID" }
    }
}
```

| 参数 | 类型 | 必须 | 官方说明(原文) |
|---|---|---|---|
| response_type | String | 是 | 「响应类型,此处固定为 update_template_card - 替换部分用户的模版」 |
| userids | String[] | 否 | 「表示要替换模版卡片消息的userid列表,**仅对群聊会话类型的卡片事件回调进行回复该字段有效**。若不填,则表示替换当前消息涉及到的所有用户」→ **单聊直接省略** |
| template_card | Object | 是 | 「要替换的模版卡片TemplateCard结构体。参考 模板卡片类型 中类型说明」→ 五种卡型都可作为替换目标(button_interaction 也行) |
| template_card.feedback.id | String | 否 | 「替换用户模板会覆盖原先消息的反馈信息……有效长度为 256 字节以内,必须是 utf-8 编码」 |

task_id 两条原文并存,按此理解:

- 101031:「**注:模板卡片中的task_id需跟回调收到的task_id一致**」→ 更新时回填事件里收到的 task_id。
- 101032:「任务id,同一个机器人任务id不能重复……**任务id只在发消息时候有效,更新消息的时候无效**。任务id将会在相应的回调事件中返回」→ 更新时它不注册新任务;唯一性约束只管首发。
- 工程解读:**替换卡里原样回填收到的 `task_id`**,既满足 101031 的一致性要求,也不会撞唯一性。

## 4. 置灰的可落地 JSON 样例

### 按钮样式官方取值(先看图)

- aibot Button 原文(101032):「style Int 否 按钮样式,**目前可填1~4,不填或错填默认1**,按钮样式如下所示:」+ 样式图。
- aibot 官方样式图(文档内嵌,[图片直链](https://wework.qpic.cn/wwpic/805842_iKxTyYPiRBamTcX_1628665323/0))实测查看:**样式1=蓝底白字(强调),样式2=浅灰底蓝字,样式3=浅灰底红字,样式4=浅灰底黑字(中性)**。
- 对照:应用消息侧样式图([94888] 内嵌,[图片直链](https://wework.qpic.cn/wwpic/800304_DpCjnJFpSGuTG_q_1629208052/0))给了官方命名:「B1:强调按键 / B2:次强调按键 / B3:中性按键 / B4:负向按键」——注意应用消息图中 3=中性、4=负向,与 aibot 图的 3(红)/4(黑) 顺序相反。**aibot 侧以 aibot 图为准:置灰观感选 `style: 4`。**

### 方案 A(推荐):整卡替换为「视觉置灰」的 button_interaction 卡

被动回复(收到 template_card_event 后 5 秒内原样返回):

```json
{
    "response_type": "update_template_card",
    "template_card": {
        "card_type": "button_interaction",
        "main_title": { "title": "发货确认", "desc": "批次 #20260827-01" },
        "horizontal_content_list": [
            { "keyname": "状态", "value": "已确认" },
            { "keyname": "操作人", "value": "张三" }
        ],
        "button_list": [
            { "text": "已确认 ✓", "style": 4, "key": "noop_confirmed_20260827-01" }
        ],
        "task_id": "<回调收到的task_id原样回填>"
    }
}
```

- 单聊不填 `userids`;`card_action` 对 button_interaction 是「否(可选)」,**省略不会 42045**。
- 局限(协议推断,见 §8):style 4 只是灰色观感,按钮**客户端仍可点击**,会再次产生 `template_card_event`(event_key = noop key)。服务端需幂等:识别 noop key 直接再回同一张卡(或忽略)。
- 若想彻底不可点:按钮列表必填(`button_list` 是「是」),没有零按钮的 button_interaction 卡;彻底去按钮只能走方案 B。

### 方案 B(现状):替换为 text_notice 卡

- 101032 原文:text_notice 的「card_action Object **是** 整体卡片的点击跳转事件,text_notice模版卡片中该字段为必填项」;CardAction「type …… **text_notice和news_notice模版卡片中该字段取值范围为[1,2]**」(1=跳转url,2=打开小程序)。
- 即:必须给整卡挂一个跳转(这就是我们踩到 42045 的原因),卡片点击会打开 url/小程序。按钮消失得干净,但「点了跳走」语义未必是我们要的。42045 官方释义见 [96213 全局错误码](https://developer.work.weixin.qq.com/document/path/96213):「42045 Template_Card.card_action 缺失或不合法」。

### 方案 C(未验证,不建议依赖):在替换卡里塞应用消息的 `replace_text`

- 应用消息线更新为新卡时,button_interaction 卡有卡级字段 `replace_text`:「按钮替换文案,**填写本字段后会展现灰色不可点击按钮**」(94888 原文,示例 `"replace_text": "已提交"`,位置与 `button_list` 同级)。这正是原生审批卡那种灰条效果。
- 但该字段**未收录进 aibot 的 101032**(全文 0 次)。客户端渲染引擎可能共用,也可能直接丢弃/报错——纯推断,需真机实测后才可采用(见 §8)。

### 附:选择器/投票卡的官方禁用(未来批量确认卡可用)

101032 原文(aibot 官方唯一的「更新即禁用」能力):

- SelectionItem:「disable Bool 否 下拉式的选择器是否不可选,false为可选,true为不可选。**仅在更新模版卡片的时候该字段有效**」
- CheckBox(vote_interaction):「disable Bool 否 投票选择框的是否不可选,false为可选,true为不可选。**仅在更新模版卡片的时候该字段有效**」

若批量确认卡改用 vote_interaction / multiple_interaction(勾选批次 + 提交按钮),提交后 update 回同卡 + `disable: true`,选择框可以真禁用;但 SubmitButton 结构只有 `text` / `key`,提交按钮本身依旧没有禁用字段。

## 5. 对照:应用消息线的「真置灰」为何 aibot 用不了

1. **被动回复 update_button**([90241 被动回复消息格式](https://developer.work.weixin.qq.com/document/path/90241),原文):
   ```xml
   <MsgType><![CDATA[update_button]]></MsgType>
   <Button><ReplaceName><![CDATA[ReplaceName]]></ReplaceName></Button>
   ```
   参数说明原文:「MsgType 消息类型,此时固定为:update_button」「Button.ReplaceName 点击卡片按钮后显示的按钮名称」。同页还有「更新点击用户的整张卡片」章节。——这是**自建应用 XML 回调**的协议,aibot 回调是另一套 JSON 协议(101031),不含此类型。
2. **update_template_card API**([94888](https://developer.work.weixin.qq.com/document/path/94888)):「更新按钮为不可点击状态:仅原卡片为 按钮交互型、投票选择型、多项选择型的卡片可以更新按钮,可以将按钮更新为不可点击状态,并且自定义文案」,请求体 `{"userids":[...],"agentid":1,"response_code":"...","button":{"replace_name":"..."}}`;「response_code 是 更新卡片所需要消费的code,可通过发消息接口和回调接口返回值获取,一个code只能调用一次该接口,且只能在72小时内调用」。——**aibot 的 template_card_event 里没有 response_code(只有 response_url),也没有 agentid**(101027 事件结构逐字核对),所以这个 API 对 aibot 卡片无法调用。
3. 结论:原生审批卡那种「按钮变灰字」效果,目前是应用消息/审批产品线的专属;aibot 在 2026-08-27 的官方文档口径下**没有等价物**。

## 6. button_interaction 字段长度上限表(aibot,101032 原文)

> 备注:官方多数是「建议不超过」,超出通常截断展示而非报错;「最长支持」「不超过」是硬约束。做批量确认卡按下表排版。

| 字段 | 必填 | 上限(原文) |
|---|---|---|
| main_title.title | 否* | 「一级标题,建议不超过26个字」;*「main_title.title和二级普通文本sub_title_text必须有一项填写」 |
| main_title.desc | 否 | 「标题辅助信息,建议不超过30个字」 |
| sub_title_text | 否* | 「二级普通文本,建议不超过112个字」 |
| source.desc | 否 | 「来源图片的描述,建议不超过13个字」 |
| horizontal_content_list | 否 | 「列表长度不超过6」 |
| ├ keyname | 是 | 「二级标题,建议不超过5个字」 |
| ├ value | 否 | 「二级文本,建议不超过26个字」 |
| └ type | 否 | 「0或不填代表是普通文本,1 代表跳转url,3 代表点击跳转成员详情」 |
| button_list | **是** | 「按钮列表,列表长度不超过6」 |
| ├ text | 是 | 「按钮文案,建议不超过10个字」 |
| ├ style | 否 | 「目前可填1~4,不填或错填默认1」(样式见 §4 图) |
| └ key | 是 | 「最长支持1024字节,不可重复」,回调作为 `event_key` 返回 |
| button_selection(下拉) | 否 | title「建议不超过13个字」;option ≤10 个;option.id「最长支持128字节,不可重复」;option.text「建议不超过10个字」;question_key ≤1024字节 |
| card_action | 否(button_interaction) | type:0/不填=不是链接,1=url,2=小程序;**text_notice/news_notice 必填且 type∈[1,2]** |
| task_id | **是**(button_interaction) | 「同一个机器人任务id不能重复,只能由数字、字母和"_-@"组成,最长128字节。任务id只在发消息时候有效,更新消息的时候无效」 |
| feedback.id(更新回复) | 否 | 「有效长度为 256 字节以内,必须是 utf-8 编码」(101031) |
| action_menu.action_list | 否 | 「列表长度取值范围为 [1, 3]」;action_list.key ≤1024字节 |

vote/multiple 卡补充(备用):vote 选项 ≤20 个、option.text「建议不超过11个字」;multiple 下拉 ≤10 项;SubmitButton.text「建议不超过10个字,不填默认为提交」、key ≤1024字节;CheckBox/SelectionItem 有 `disable` 布尔(仅更新时有效,见 §4)。

## 7. 来源清单(全部 2026-08-27 实际抓取验证)

| # | 文档 | URL | 用途 |
|---|---|---|---|
| 1 | aibot 接收消息 | https://developer.work.weixin.qq.com/document/path/100719 | 回调入口,指向 101031/101032 |
| 2 | aibot 接收事件 | https://developer.work.weixin.qq.com/document/path/101027 | template_card_event 结构、5 秒/仅一次、response_url |
| 3 | aibot 被动回复消息 | https://developer.work.weixin.qq.com/document/path/101031 | 响应清单、update_template_card 结构、task_id 一致性注 |
| 4 | aibot 模板卡片类型 | https://developer.work.weixin.qq.com/document/path/101032 | Button/CardAction/长度上限、selector/checkbox 的 disable |
| 5 | aibot 主动回复消息 | https://developer.work.weixin.qq.com/document/path/101138 | response_url 1h/一次、仅 markdown/template_card |
| 6 | aibot 长连接 | https://developer.work.weixin.qq.com/document/path/101463 | aibot_respond_update_msg 等价性 |
| 7 | API模式机器人文档使用说明 | https://developer.work.weixin.qq.com/document/path/101468 | aibot 文档树 |
| 8 | 应用消息·被动回复消息格式 | https://developer.work.weixin.qq.com/document/path/90241 | update_button + Button.ReplaceName 原文(对照) |
| 9 | 应用消息·更新模版卡片消息 | https://developer.work.weixin.qq.com/document/path/94888 | button.replace_name、replace_text、response_code 72h(对照) |
| 10 | 全局错误码 | https://developer.work.weixin.qq.com/document/path/96213 | 「42045 Template_Card.card_action 缺失或不合法」 |
| 11 | 发送应用消息(用户所给 90372 实际页面) | https://developer.work.weixin.qq.com/document/path/90372 | 确认与 aibot 无关 |
| 12 | aibot 按钮样式图 | https://wework.qpic.cn/wwpic/805842_iKxTyYPiRBamTcX_1628665323/0 | style 1~4 视觉(1蓝白/2灰蓝/3灰红/4灰黑) |
| 13 | 应用消息按钮样式图 | https://wework.qpic.cn/wwpic/800304_DpCjnJFpSGuTG_q_1629208052/0 | B1强调/B2次强调/B3中性/B4负向命名 |

未能取证:群机器人「消息推送」正文(91770/91880 页面为 JS 客户端渲染,curl 拿不到正文;与本题无关,未下结论)。

## 8. 推断与待实测项(非官方文字结论)

1. **「style:4 灰色按钮仍可点击」是协议推断**:aibot Button 无任何禁用字段 ⇒ 客户端没有禁点依据。置信度高,但点击后的实际回调行为(以及连点表现)建议真机验证一次。
2. **方案 C 的 `replace_text` 在 aibot 是否被渲染:纯推断,未验证**。仅应用消息 94888 收录;塞进 aibot 替换卡可能被忽略、也可能整包被拒。上线前必须实测,且不应作为主方案。
3. style 数值→颜色映射来自官方文档内嵌样式图(图内自带「按钮样式1/2/3/4」标注,算主源);但官方**文字**从未定义各值语义,未来客户端改版可能调整视觉。
4. task_id 的两句原文(§3)存在轻微张力,「更新时原样回填」是二者的合取解读;若实测发现更新时可省略 task_id,以实测为准。
