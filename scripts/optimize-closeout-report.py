from pathlib import Path
import re
import json
import hashlib
import subprocess
from html.parser import HTMLParser

root = Path(__file__).resolve().parents[1]
source = root / 'docs/代码智库项目结项报告.html'
html = source.read_text(encoding='utf-8')
replacements = {
    '代码智库 · 项目结项报告': '代码智库 · 阶段结项报告',
    '软件已交付 · 业务成效待试点': '软件包已形成 · 提请阶段验收',
    '正式交付版本': '本次评审版本',
    '汇报日期': '成果记录日期',
    '本次结项，交付了什么': '本阶段形成了哪些成果',
    '正式离线交付': '离线部署包已形成',
    'MCP 工具实测通过': 'HTTP 样例工具调用通过',
    '一个离线镜像，落地内网环境': '提供面向内网部署的离线镜像',
    '交付有验证，结果有记录': '专项验证有记录，整体验收待确认',
    '按交付范围结项，用试点检验价值': '确认阶段范围，再形成结项意见',
    '软件与离线包已交付': '软件与离线包已形成',
    '建议按已交付范围<br><span class="accent">完成技术结项</span>': '提请阶段成果验收<br><span class="accent">结项以确认意见为准</span>',
    '业务成效待试点验证。': '原目标未全部完成；范围调整须由评审确认。',
    '<h3>开展 ≥3 个项目试点</h3><p>记录接手、定位耗时与知识覆盖。</p>': '<h3>确认范围与遗留事项</h3><p>自动抽取、业务全景及 ≥3 项目试点列明处置。</p>',
    '<h3>完成目标环境验收</h3><p>真实模型 / 远端接入 / 容量 / 备份恢复</p>': '<h3>补齐目标环境与质量验证</h3><p>真实模型 / 远端接入 / 质量 / 容量 / 恢复</p>',
    '<h3>按试点需要扩展</h3><p>知识库转代码 / 附件解析 / 业务抽取</p>': '<h3>完成移交与责任确认</h3><p>源码快照 / 接收记录 / 负责人 / 期限 / 验收标准</p>',
    '依据：代码实现 / 离线包 / 验证记录': '建议提交评审；本报告不代替范围批准及验收签署',
    '核心已交付': '核心已实现',
    '待验 / 待补：<br>业务知识自动抽取': '尚未完成：<br>业务知识自动抽取',
    '待验 / 待补：<br>真实模型 / 质量评测': '尚待验证：<br>真实模型效果 / 质量评测',
    '待验 / 待补：<br>业务全景 / ≥3 项目试点': '尚未完成：<br>业务全景 / ≥3 项目试点',
    '原方案价值目标 · 尚无达成数据': '原方案价值目标 · 未取得实测结论，统计口径须先确认',
    '定位耗时目标 ≤20 分钟': '≤20 分钟；效率计算口径待定',
    '测试记录截至 2026-10-12；不同批次分别列示。': '构建与测试记录截至 2026-10-12，MCP 调用记录截至 2026-10-13；不同批次分别列示。',
}
for old, new in replacements.items():
    if old not in html:
        raise ValueError(f'Missing replacement: {old}')
    html = html.replace(old, new)

html = html.replace('</style>', '''
/* Stable report revision: retain the original visual language. */
.target-value{font-size:27px;color:var(--muted)}
.target-value small{font-size:17px}
.target-item{gap:13px;padding:16px}.target-item .caption{font-size:14px}
.closeout-title{font-size:39px!important}.closing-note{font-size:18px!important}
.scope-note{margin-top:20px;font-size:16px;line-height:1.65;color:var(--muted)}
.report-record{margin:18px 0;padding:18px;border:1px solid var(--line);border-radius:12px;font-size:16px;line-height:1.8}
.report-record strong{color:var(--cyan)}
.unavailable-source{color:var(--muted);font-size:15px}
</style>''')
html = html.replace('<div class="cover-meta">', '<p class="scope-note">评审对象：1.1.3 历史交付批次<br>文稿修订：2026.10.20 · 当前软件版本以根目录 VERSION 为准</p><div class="cover-meta">', 1)
html = html.replace('11 项按环境跳过', '记录含 11 项环境跳过，未计通过')
html = html.replace('批次独立列示；跳过项与质量目标未计为通过', '历史批次分别列示；跳过项影响及整体验收仍须确认')
html = html.replace('<div class="reference-grid">', '''<div class="report-record"><strong>报告适用范围与结项条件</strong><br>
本稳定稿修订于 2026-10-20，仅整理 1.1.3 批次的已有成果与验证记录，不将后续版本或本次文稿修订视为重新测试。报告中的“已形成”“已实现”不等于接收方已验收。<br>
原方案 M1、M3 尚有未完成内容，M2 效果待验证。未取得范围调整批准前，不认定原立项目标全部完成。正式结项须确认阶段范围、验收意见与遗留事项处置。<br>
遗留事项建议归口：项目负责人组织范围确认及试点；研发与测试补充模型、质量及远端接入验证；运维与接收方完成容量、备份恢复及移交。上述为建议分工，具体负责人、截止日期和验收标准须在评审中落实。<br>
业务抽取、业务全景及 ≥3 项目试点作为原目标差异单独跟踪；知识库转代码与附件正文解析作为后续需求另行确认。<br>
归档要求：提供与镜像匹配的源码快照及校验值、部署包、测试报告、接收记录和遗留清单。sourceDirty=true 表示仅凭提交号不足以还原交付源码；本次未补建历史源码快照。当前源码链接仅供定位，不作为 1.1.3 源码快照。
</div><div class="reference-grid">''', 1)

# Keep process evidence available without making it the closeout narrative.
appendix = []
page_content = {
    15: ('项目交付成果', '交付成果', '''
<p class="closeout-lead">围绕代码与知识联合检索，形成软件、部署和文档三类成果。</p>
<div class="grid3 closeout-cards">
<div class="glass"><span class="governance-label">软件系统</span><h3>工作台与业务服务</h3><p>项目接入与分支阅读<br>联合检索与证据问答<br>知识治理、权限及任务管理</p><small>状态：主要功能已实现</small></div>
<div class="glass"><span class="governance-label">部署成果</span><h3>离线安装与维护</h3><p>1.1.3 单镜像部署包<br>编排、启停与配置模板<br>部署说明及包内外校验文件</p><small>状态：部署包与专项记录已形成</small></div>
<div class="glass"><span class="governance-label">技术资料</span><h3>源码与配套文档</h3><p>前后端源码与数据库初始化<br>MCP 接入及使用说明<br>评测样本与专项验证记录</p><small>状态：资料已形成，版本归档待核对</small></div>
</div><div class="strip gap-top">交付基准：1.1.3 · Linux amd64 · 全新安装；正式移交需附清单、源码快照与接收确认。</div>
''', '成果物已形成；接收签署与源码快照归档待确认'),
    16: ('项目完成情况与边界', '完成情况', '''
<p class="closeout-lead">核心使用闭环已形成，原目标差异和运行效果分别说明。</p>
<div class="grid3 closeout-cards">
<div class="glass"><span class="governance-label">已实现</span><h3>核心业务闭环</h3><p>接入项目与准备分支<br>维护知识、检索与问答<br>核对原文、版本与静态关系</p><small>支持代码项目及纯知识库使用</small></div>
<div class="glass"><span class="governance-label amber">尚未完成</span><h3>原目标差异</h3><p>业务知识自动抽取<br>业务全景能力<br>不少于 3 个项目的试点</p><small>须确认补齐安排或范围调整</small></div>
<div class="glass"><span class="governance-label amber">尚待验证</span><h3>效果与目标环境</h3><p>真实模型效果与检索质量<br>真实远端接入及容量<br>目标环境备份恢复与业务收益</p><small>专项通过不等于整体验收通过</small></div>
</div><div class="strip gap-top">使用边界：图谱提供静态线索；引用支持人工核对；附件已支持存储下载，正文解析未交付。</div>
''', '原目标未全部完成；详细对照与历史测试记录见依据附录'),
    17: ('结项意见与移交安排', '结项与移交', '''
<div class="closeout-decision glass"><span class="pill">阶段结项建议</span><h3>提请验收已形成成果，结项以评审确认意见为准。</h3><p>以 1.1.3 批次为评审对象；确认阶段范围、验收结论及遗留事项后，完成成果归档与接管。</p></div>
<div class="grid3 closeout-cards handover-cards">
<div class="glass"><span class="governance-label">范围与验收</span><h3>明确本次结项依据</h3><p>确认交付清单与阶段范围<br>原目标差异逐项列明处置<br>记录接收方验收意见</p><small>建议归口：项目负责人、接收方</small></div>
<div class="glass"><span class="governance-label">归档与接管</span><h3>确保成果可接续</h3><p>匹配镜像的源码快照及校验<br>部署、配置与数据位置说明<br>维护权限和备份恢复交接</p><small>建议归口：研发、运维、接收方</small></div>
<div class="glass"><span class="governance-label">遗留事项</span><h3>落实后续责任</h3><p>补充质量和目标环境验收<br>组织试点并记录业务效果<br>逐项明确责任人、期限与标准</p><small>具体人员及日期由评审确认</small></div>
</div>
''', '本页为建议安排；范围批准、接收签署及具体责任尚待确认'),
}
for number, (title, eyebrow, body, footer) in page_content.items():
    pattern = rf'<section class="slide [^"]*" id="slide-{number}".*?</section>'
    old_section = re.search(pattern, html, re.S).group(0)
    old_title = re.search(r'data-title="([^"]+)"', old_section).group(1)
    if number in (15, 16):
        old_body = re.search(r'<div class="slide-body">(.*)</div><footer', old_section, re.S).group(1)
        appendix.append(f'<details class="source-details"><summary>附录：{old_title}</summary><div class="report-record">{old_body}</div></details>')
    new_section = f'<section class="slide " id="slide-{number}" data-title="{title}" aria-label="第 {number} 页，共 18 页：{title}" aria-hidden="true" inert><header><div class="heading"><p class="eyebrow">{eyebrow}</p><h2>{title}</h2></div></header><div class="slide-body">{body}</div><footer class="slide-footer"><span>代码智库 · 项目结项汇报</span><span>{footer}</span><span>{number:02} / 18</span></footer></section>'
    html = html.replace(old_section, new_section)
    html = html.replace(f'<span>{number:02}</span>{old_title}</button>', f'<span>{number:02}</span>{title}</button>')
html = html.replace('<div class="evidence-note">', ''.join(appendix) + '<div class="evidence-note">', 1)
html = html.replace('</style>', '''
.closeout-lead{font-size:23px;color:#cbd5e1;margin:0 0 26px;line-height:1.6}
.closeout-cards>.glass{padding:25px 26px;min-width:0}
.closeout-cards h3{font-size:25px;margin:10px 0 20px}
.closeout-cards p{font-size:20px;line-height:2;color:#cbd5e1}
.closeout-cards small{display:block;font-size:16px;color:var(--muted);margin-top:22px;line-height:1.7}
.closeout-decision{padding:22px 26px;margin-bottom:22px}
.closeout-decision h3{font-size:25px;margin:12px 0 8px}
.closeout-decision p{font-size:18px;color:var(--muted);line-height:1.7}
.handover-cards h3{font-size:23px;margin-bottom:14px}
.handover-cards p{font-size:18px;line-height:1.9}
.handover-cards small{font-size:15px;margin-top:15px}
@media(max-width:650px){body.reading .closeout-cards{grid-template-columns:1fr}}
</style>''')

out = root / 'release/结项报告-1.1.3'
out.mkdir(exist_ok=True)
missing = []
def fix_link(match):
    href = match.group(1)
    if href.startswith(('#', 'http:', 'https:', 'mailto:')):
        return match.group(0)
    path, _, fragment = href.partition('#')
    target = (source.parent / path).resolve()
    if not target.exists():
        missing.append(href)
        return 'data-unavailable-source="' + href + '" aria-disabled="true" title="原始依据文件当前缺失，须补充归档"'
    relative = '../../' + target.relative_to(root).as_posix()
    return 'href="' + relative + (('#' + fragment) if fragment else '') + '"'
html = re.sub(r'href="([^"]+)"', fix_link, html)
html = html.replace('依据链接依赖同目录项目文件。', '依据链接依赖原项目目录；缺失依据已禁用并标注，不作为已核验材料。')
html = html.replace('<title>代码智库 · 阶段结项报告</title>', '<title>代码智库 · 阶段结项报告 · 稳定稿</title>')
target = out / '代码智库项目结项报告.html'
target.write_text(html, encoding='utf-8')
(out / 'README.md').write_text('''# 代码智库阶段结项报告（稳定稿）

打开同目录 HTML 即可演示，支持方向键翻页、目录、阅读模式和打印为 PDF。

- 评审对象：软件 1.1.3 历史批次，不代表当前最新软件版本。
- 文稿修订日期：2026-10-20；未重新执行软件测试。
- 保留原报告，不覆盖历史交付包。当前正式汇报建议使用本目录稳定稿。
- HTML 展示无需联网；依据链接指向原仓库，单独发送 HTML 时这些链接无法随附。
- 当前源码链接仅供定位，不是交付时源码快照；#L 行号锚点不保证本地浏览器定位。
- 未补造范围批准、接收签署、负责人、期限及测试结论。正式结项需由评审确认。
- 第15—17页改为交付成果、完成情况与边界、结项意见与移交安排；历史测试及原目标对照移入“依据与归档”附录。
''', encoding='utf-8')
(out / '依据检查.json').write_text(json.dumps({'reportSoftwareVersion':'1.1.3','revised':'2026-10-20','missingReferences':missing,'note':'仅检查文件存在性，不代表内容或历史版本一致性验证'}, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'output':str(target),'missing':missing}, ensure_ascii=False))
ids = re.findall(r' id="([^"]+)"', html)
assert len(ids) == len(set(ids)), 'Duplicate element IDs'
assert len(re.findall(r'<section class="slide', html)) == 18
assert len(re.findall(r'data-slide=', html)) == 18
HTMLParser().feed(html)
subprocess.run(['node', '--check'], input=re.search(r'<script>(.*?)</script>', html, re.S).group(1), text=True, check=True)
verification = {'slides':18, 'navigationEntries':18, 'uniqueIds':True, 'javascriptSyntax':'passed', 'missingReferences':missing, 'visualReview':'not performed: local file preview blocked by browser policy', 'softwareTests':'not rerun; document-only revision'}
(out / '检查记录.json').write_text(json.dumps(verification, ensure_ascii=False, indent=2), encoding='utf-8')
checksums = []
for artifact in sorted(out.iterdir()):
    if artifact.is_file() and artifact.name != 'SHA256SUMS':
        checksums.append(hashlib.sha256(artifact.read_bytes()).hexdigest() + '  ' + artifact.name)
(out / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n', encoding='utf-8')
print('Document structure, navigation, local references and JavaScript syntax checked.')
