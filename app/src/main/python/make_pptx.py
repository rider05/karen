"""Karen on-device PPT generator (stdlib only — no python-pptx needed).

Runs inside Chaquopy on the phone: writes a real .pptx (zip + OOXML) to the
absolute output path, then re-opens it to verify slide count. Called from
PptxBuilder via com.chaquoyle.python.Python. Same minimal professional
theme as tools/make_pptx.py: white bg, #0F172A titles, #2563EB accent.
"""

import zipfile


def _esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _slide_xml(title, bullets, is_title=False, subtitle=""):
    body = (
        '<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>'
        '<p:spPr><a:xfrm><a:off x="228600" y="228600"/><a:ext cx="8686800" cy="1371600"/></a:xfrm></p:spPr>'
        '<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="%d" b="1">'
        '<a:solidFill><a:srgbClr val="0F172A"/></a:solidFill></a:rPr><a:t>%s</a:t></a:r></a:p>'
        % (4000 if is_title else 3200, _esc(title))
    )
    if is_title and subtitle:
        body += (
            '<a:p><a:r><a:rPr lang="en-US" sz="1600"><a:solidFill><a:srgbClr val="475569"/></a:solidFill>'
            '</a:rPr><a:t>%s</a:t></a:r></a:p>' % _esc(subtitle)
        )
    body += "</p:txBody></p:sp>"
    lst = (
        '<p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>'
        '<p:spPr><a:xfrm><a:off x="228600" y="2057400"/><a:ext cx="8686800" cy="3657600"/></a:xfrm></p:spPr>'
        "<p:txBody><a:bodyPr/><a:lstStyle/>"
    )
    for b in bullets:
        lst += (
            '<a:p><a:r><a:rPr lang="en-US" sz="1800"><a:solidFill><a:srgbClr val="334155"/>'
            "</a:solidFill></a:rPr><a:t>%s</a:t></a:r></a:p>" % _esc(b)
        )
    lst += "</p:txBody></p:sp>"
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
        'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:bg><p:bgPr>'
        '<a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></p:bgPr></p:bg><p:cSld><p:spTree>'
        '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:grpSpPr/><p:nvPr/></p:nvGrpSpPr>'
        "<p:grpSpPr/>" + body + lst + "</p:spTree></p:cSld></p:sld>"
    )


def _presentation_xml(n):
    sld_ids = "".join('<p:sldId id="%d" r:id="rId%d"/>' % (255 + i, i + 1) for i in range(1, n + 1))
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>'
        "<p:sldIdLst>%s</p:sldIdLst>"
        '<p:sldSz cx="12192000" cy="6858000" type="wide"/>'
        '<p:notesMasterIdLst><p:notesMasterId r:id="rId%d"/></p:notesMasterIdLst></p:presentation>'
        % (sld_ids, n + 2)
    )


def _content_types(n):
    slides = "".join(
        '<Override PartName="/ppt/slides/slide%d.xml" ContentType="application/vnd.openxmlformats-'
        'officedocument.presentationml.slide+xml"/>' % i
        for i in range(1, n + 1)
    )
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'
        '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>'
        '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>'
        '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>'
        '<Override PartName="/ppt/notesMasters/notesMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml"/>'
        "%s</Types>" % slides
    )


def _pres_rels(n):
    slide_rels = "".join(
        '<Relationship Id="rId%d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide%d.xml"/>'
        % (i + 1, i)
        for i in range(1, n + 1)
    )
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>'
        "%s"
        '<Relationship Id="rId%d" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster" Target="notesMasters/notesMaster1.xml"/>'
        "</Relationships>" % (slide_rels, n + 2)
    )


_ROOT_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>'
    "</Relationships>"
)

_MASTER = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
    'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
    "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:grpSpPr/><p:nvPr/></p:nvGrpSpPr>"
    "<p:grpSpPr/></p:spTree></p:cSld></p:sldMaster>"
)

_LAYOUT = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
    'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
    "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:grpSpPr/><p:nvPr/></p:nvGrpSpPr>"
    "<p:grpSpPr/></p:spTree></p:cSld></p:sldLayout>"
)

_THEME = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Karen">'
    '<a:themeElements><a:clrScheme name="Karen"><a:dk1><a:sysClr val="windowText" lastClr="0F172A"/></a:dk1>'
    '<a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1E293B"/></a:dk2>'
    '<a:lt2><a:srgbClr val="F8FAFC"/></a:lt2><a:accent1><a:srgbClr val="2563EB"/></a:accent1>'
    '<a:accent2><a:srgbClr val="34D399"/></a:accent2><a:accent3><a:srgbClr val="FBBF24"/></a:accent3>'
    '<a:accent4><a:srgbClr val="F472B6"/></a:accent4><a:accent5><a:srgbClr val="94A3B8"/></a:accent5>'
    '<a:accent6><a:srgbClr val="0F172A"/></a:accent6><a:hlink><a:srgbClr val="2563EB"/></a:hlink>'
    '<a:folHlink><a:srgbClr val="1E3A8A"/></a:folHlink></a:clrScheme>'
    '<a:fontScheme name="Karen"><a:majorFont><a:latin typeface="Calibri"/></a:majorFont>'
    '<a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme>'
    '<a:fmtScheme name="Karen"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>'
    '<a:lnStyleLst><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>'
    '<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>'
    '<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>'
    "</a:themeElements></a:theme>"
)

_NOTES_MASTER = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<p:notesMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
    'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
    "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:grpSpPr/><p:nvPr/></p:nvGrpSpPr>"
    "<p:grpSpPr/></p:spTree></p:cSld></p:notesMaster>"
)

_SLIDE_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>'
    "</Relationships>"
)


def build_pptx(title, slides, out_path, subtitle="A minimal, professional deck."):
    n = max(4, min(40, int(slides)))
    titles = [title, "Agenda"]
    sections = [
        "Why it matters", "What we cover", "Key ideas", "How it works", "By the numbers",
        "Checklist", "Budget", "Examples", "Watch-outs", "Next steps", "Q&A", "Thanks",
    ]
    for i in range(n - 3):
        titles.append(sections[i % len(sections)])
    titles.append("Thanks")
    titles = titles[:n]
    while len(titles) < n:
        titles.append(titles[-1])

    def bullets_for(i):
        if i == 0:
            return [subtitle]
        if i == 1:
            return ["• %s" % t for t in titles[1:-1]]
        if i == n - 1:
            return ["Questions welcome."]
        return [
            "Illustrative editorial example — edit before presenting.",
            "Facts from public sources where stated.",
            "One idea per slide; keep text short.",
        ]

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        def put(name, content):
            z.writestr(name, content)
        put("[Content_Types].xml", _content_types(n))
        put("_rels/.rels", _ROOT_RELS)
        put("ppt/presentation.xml", _presentation_xml(n))
        put("ppt/_rels/presentation.xml.rels", _pres_rels(n))
        put("ppt/slideMasters/slideMaster1.xml", _MASTER)
        put("ppt/slideLayouts/slideLayout1.xml", _LAYOUT)
        put("ppt/theme/theme1.xml", _THEME)
        put("ppt/notesMasters/notesMaster1.xml", _NOTES_MASTER)
        for i in range(1, n + 1):
            put("ppt/slides/slide%d.xml" % i, _slide_xml(titles[i - 1], bullets_for(i - 1), i == 1, subtitle))
            put("ppt/slides/_rels/slide%d.xml.rels" % i, _SLIDE_RELS)

    # Verify: re-open and count slides.
    with zipfile.ZipFile(out_path, "r") as z:
        count = sum(1 for name in z.namelist() if name.startswith("ppt/slides/slide") and name.endswith(".xml"))
    return count
