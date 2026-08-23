---
status: accepted
---

# AntD is for interactive controls, never for page structure

用户 2026-08-23 提问「一定要用 antd 吗」，量化后定界（而非移除）。

## 事实

- 58 / 77 个 tsx 引用 antd；antd 打包 1.3 MB（echarts 1.0 MB、业务代码 426 KB）。
- 用量分两类，价值悬殊：
  - **展示类**（Typography 28 / Space 24 / Alert 22 / Tag 21 / Card 19 / Descriptions 13）——
    用组件默认密度堆布局，正是「AI 味 / 模板脸」的来源，替换成本极低。
  - **交互控件类**（Select 27 / Form 14 / Table 13 / Drawer 10 / Modal 10 / DatePicker 8 /
    Upload 3）——焦点管理、表单校验、焦点陷阱、键盘可达性都在里面，自研是 bug 温床
    （岗位下拉自研 listbox 已在评审中暴露方向键与焦点交还缺失，那还是最简单的一种）。

## Decisions

1. **页面结构与展示层一律手写 `zs-` CSS**（原型移植，密度自控）：页头、区块、卡片、
   指标、链路、表格、告警、标签、说理框。
2. **交互控件继续用 AntD**：Select / Form / Table / Drawer / Modal / DatePicker / Upload /
   Button，继承 `saasTheme` token（34px 控件高、8px 圆角、品牌色），与手写区块视觉一致。
3. **禁止用 AntD 的 Card / Space / Typography / Row / Col / Flex 搭页面骨架**——
   由测试守门（`antdBoundary.test.ts`）对已迁移模块清单强制，清单只增不减（棘轮）。
4. 既有对象页按批次迁移展示层，不设截止期；迁移一个就加进守门清单。
5. 不移除 AntD：明天要现场演示，重写交互控件是拿演示冒险；1.3 MB 是 gzip 前数字，
   内网 ERP 无感；真要减体积，先砍 echarts（仅分析页用）比砍 AntD 划算。

## Considered options

- 全面移除 AntD 自研设计系统：rejected —— 交互控件的可访问性与表单校验是几周工作量
  且长期维护成本高；观感问题的真因是布局层而非控件层。
- 维持现状（继续用 AntD 布局）：rejected —— 用户两次否决该观感，且已验证手写展示层
  可解决问题（外壳与四个工作台）。
- 换第三方 headless 组件库（Radix / Ark）：rejected（本轮）—— 引入新依赖与迁移成本，
  但若将来 Table 成为瓶颈可单独评估该条路径。
