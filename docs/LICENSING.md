# Xs 自定义许可方向 / Custom-license direction

日期：2026-11-06。
状态：用户已同意 Xs 可以调整为自定义源码许可；本文件是决策记录，**不是已经生效的新许可文本**。

## 已确认

不再把根目录当前采用 GPL-3.0 当作无法调整的产品前提。可以参考 ChatGPT Studio 的许可组织方式，为 Xs 自有代码形成单独文本和必要的跨项目代码授权记录。仓库继续公开；公开源码与采用 OSI 标准开源许可不是同一个声明。

## 本次尚未擅自决定

“允许改为自定义”没有给出最终商业/非商业、再分发、贡献者和授权范围条款，本次不自行增加新的非商业限制或商业收费条件。当前根目录 LICENSE 仍为 GPL-3.0；正式新文本发布前，不把代码或发布包改标成已经完成重新许可。

## 实施要求

1. 列出拟按新文本发布的自有文件、版权/贡献者来源，以及跨项目复用的版本与权利范围。不能仅因仓库在同一用户名下就推断所有第三方权利归项目所有者。
2. 保留第三方依赖、复制代码和资源的原版权、NOTICE 和许可义务。不能用项目新声明覆盖它们。
3. 既有合法取得的 GPL 版本及其权利不因未来版本采用新许可而被追溯撤销。不重写 Git 历史删除旧许可。
4. 正式文本可单独规定自有代码的使用、修改、再分发与商业授权；需由权利方明确对应文件和生效版本。不要把 GNU 许可证原文改名充作自定义正文。
5. X、OpenAI/ChatGPT、媒体作者和其他第三方服务/商标的许可不是代码许可授予的。用户的帖子、下载媒体和私人资料也不因此转移所有权。
6. 最终切换必须同步 LICENSE、双语 README、第三方声明、构建/发布文案，并进行独立许可审查。没有完成时不宣称“所有代码均可按自定义许可任意迁移”。

## English status

The owner approves considering a custom source license for Xs. This record does not itself relicense the repository, invent commercial restrictions, revoke historical GPL grants, or override third-party notices. The root LICENSE remains authoritative until a separately finalized, scope-specific license transition is made. This is an engineering decision record, not a legal opinion.

References checked 2026-11-06:
- https://www.gnu.org/licenses/gpl-faq.html
- https://www.gnu.org/licenses/gpl-3.0.html
