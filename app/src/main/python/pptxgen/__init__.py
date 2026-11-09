"""Karen on-device PPTX generator.

Stamps slides into the bundled python-pptx blank template (template.pptx in
this package) so the skeleton parts — theme, master, layouts, content types —
are exactly what python-pptx produces and PowerPoint opens without "repair".
The old hand-rolled skeleton failed PowerPoint: OOXML style lists require >= 3
entries (e.g. fillStyleLst), a minimal 1-entry theme is schema-invalid.
stdlib only; runs inside Chaquopy CPython 3.11.
"""

import io
import pkgutil
import zipfile

_NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_CT_SLIDE = (
    "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
)

_SLIDE_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="' + _NS_R + '/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>'
    "</Relationships>"
)


def _esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _slide_xml(title, bullets, is_title=False, subtitle=""):
    body = (
        '<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>'
        '<p:spPr><a:xfrm><a:off x="228600" y="228600"/><a:ext cx="11277600" cy="1371600"/></a:xfrm></p:spPr>'
        '<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="%d" b="1">'
        '<a:solidFill><a:srgbClr val="0F172A"/></a:solidFill></a:rPr><a:t>%s</a:t></a:r></a:p>'
        % (4000 if is_title else 3200, _esc(title))
    )
    if is_title and subtitle:
        body += (
            '<a:p><a:r><a:rPr lang="en-US" sz="1600"><a:solidFill><a:srgbClr val="475569"/></a:solidFill>'
            "</a:rPr><a:t>%s</a:t></a:r></a:p>" % _esc(subtitle)
        )
    body += "</p:txBody></p:sp>"
    lst = (
        '<p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>'
        '<p:spPr><a:xfrm><a:off x="228600" y="2057400"/><a:ext cx="11277600" cy="3657600"/></a:xfrm></p:spPr>'
        "<p:txBody><a:bodyPr/><a:lstStyle/>"
    )
    for b in bullets:
        lst += (
            '<a:p><a:r><a:rPr lang="en-US" sz="1800"><a:solidFill><a:srgbClr val="334155"/>'
            "</a:solidFill></a:rPr><a:t>%s</a:t></a:r></a:p>" % _esc(b)
        )
    if not bullets:
        lst += "<a:p/>"
    lst += "</p:txBody></p:sp>"
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" '
        'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree>'
        '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>'
        "<p:grpSpPr/>" + body + lst + "</p:spTree></p:cSld></p:sld>"
    )


def _plan(title, n, subtitle):
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
    return titles


def build_pptx(title, slides, out_path, subtitle="A minimal, professional deck."):
    n = max(4, min(40, int(slides)))
    titles = _plan(title, n, subtitle)

    zin = zipfile.ZipFile(io.BytesIO(pkgutil.get_data(__name__, "template.pptx")))
    pres = zin.read("ppt/presentation.xml").decode()
    rels = zin.read("ppt/_rels/presentation.xml.rels").decode()
    cts = zin.read("[Content_Types].xml").decode()

    sld_ids, rel_adds, ct_adds = "", "", ""
    for i in range(1, n + 1):
        sld_ids += '<p:sldId id="%d" r:id="rId%d"/>' % (256 + i, 6 + i)
        rel_adds += (
            '<Relationship Id="rId%d" Type="' % (6 + i)
            + _NS_R + '/slide" Target="slides/slide%d.xml"/>' % i
        )
        ct_adds += '<Override PartName="/ppt/slides/slide%d.xml" ContentType="%s"/>' % (i, _CT_SLIDE)

    # Template is a blank 4:3 deck with no sldIdLst — add it and widen to 16:9
    # (slide coordinates below are authored for 12192000 x 6858000).
    pres = pres.replace(
        '<p:sldSz cx="9144000" cy="6858000" type="screen4x3"/>',
        "<p:sldIdLst>%s</p:sldIdLst>"
        '<p:sldSz cx="12192000" cy="6858000" type="screen16x9"/>' % sld_ids,
    )
    rels = rels.replace("</Relationships>", rel_adds + "</Relationships>")
    cts = cts.replace("</Types>", ct_adds + "</Types>")

    def bullets_for(i):
        if i == 0:
            return []  # title + subtitle already on the slide; body stays empty
        if i == 1:
            return ["• %s" % t for t in titles[1:-1]]
        if i == n - 1:
            return ["Questions welcome."]
        return [
            "Illustrative editorial example — edit before presenting.",
            "Facts from public sources where stated.",
            "One idea per slide; keep text short.",
        ]

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zout:

        def put(name, content):
            zout.writestr(name, content)

        for item in zin.infolist():
            name = item.filename
            if name == "ppt/presentation.xml":
                put(name, pres)
            elif name == "ppt/_rels/presentation.xml.rels":
                put(name, rels)
            elif name == "[Content_Types].xml":
                put(name, cts)
            else:
                zout.writestr(item, zin.read(name))
        for i in range(1, n + 1):
            put(
                "ppt/slides/slide%d.xml" % i,
                _slide_xml(titles[i - 1], bullets_for(i - 1), i == 1, subtitle),
            )
            put("ppt/slides/_rels/slide%d.xml.rels" % i, _SLIDE_RELS)

    # Verify: re-open and count slides.
    with zipfile.ZipFile(out_path, "r") as z:
        count = sum(
            1 for name in z.namelist()
            if name.startswith("ppt/slides/slide") and name.endswith(".xml")
        )
    return count
