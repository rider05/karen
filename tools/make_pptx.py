#!/usr/bin/env python3
"""Karen PPT generator — real, PowerPoint-openable .pptx (python-pptx, no mock).

Usage: python tools/make_pptx.py "Topic" 8 output.pptx
Reads title + slide count from args (defaults: "Acme", 8, apks/acme_presentation.pptx).
Minimal professional theme: white bg, #0F172A titles, #2563EB accent bar, Calibri.
Verification: file is re-opened with python-pptx and slide count/titles printed.
"""
import sys

TITLE = sys.argv[1] if len(sys.argv) > 1 else "Acme"
N = int(sys.argv[2]) if len(sys.argv) > 2 else 8
OUT = sys.argv[3] if len(sys.argv) > 3 else "apks/acme_presentation.pptx"

from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN

INK = RGBColor(0x0F, 0x17, 0x2A)       # slate-900
MUTED = RGBColor(0x47, 0x55, 0x69)     # slate-600
BODY = RGBColor(0x33, 0x41, 0x55)      # slate-700
ACCENT = RGBColor(0x25, 0x63, 0xEB)    # #2563EB

prd = Presentation()
prd.slide_width = Inches(13.33)
prd.slide_height = Inches(7.5)

SECTIONS = [
    ("Why it matters", ["Saves time spent switching between apps.", "One shared source of truth.", "Works offline — nothing leaves the device."]),
    ("What we cover", ["The problem in one slide.", "The approach step by step.", "Proof and numbers."]),
    ("Key ideas", ["Start with the simplest version.", "Measure what actually matters.", "Iterate in small, visible steps."]),
    ("How it works", ["Capture → plan → act → review.", "Every step leaves a visible trace.", "Failures are honest, not hidden."]),
    ("By the numbers", ["Illustrative editorial example.", "Edit these figures before presenting.", "Cite public sources where stated."]),
    ("Checklist", ["Prep materials.", "Book the essentials first.", "Tear-out list on slide 7."]),
    ("Budget", ["Stay 45% · Food 30%.", "Transit 15% · Fun 10%.", "Adjust to your situation."]),
    ("Examples", ["Scenario A: a group trip.", "Scenario B: a solo plan.", "Scenario C: a full-day workshop."]),
    ("Watch-outs", ["Don't overfill slides.", "One idea per slide.", "Large, readable type."]),
    ("Next steps", ["Pick one action today.", "Review the trace tonight.", "Iterate tomorrow."]),
    ("Q&A", ["Questions welcome.", "Contact details in the notes."]),
]

titles = [TITLE, "Agenda"] + [s[0] for s in SECTIONS][:max(0, N - 3)] + ["Thanks"]
titles = titles[:N]
while len(titles) < N:
    titles.append(titles[-1])
bullets = dict(SECTIONS)
agenda = [f"• {t}" for t in titles[1:-1]] or ["Agenda"]

def add_slide(title, items, is_title=False):
    slide = prd.slides.add_slide(prd.slide_layouts[6])  # blank
    # white bg
    fill = slide.background.fill
    fill.solid()
    fill.fore_color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
    # accent bar
    bar = slide.shapes.add_shape(1, Inches(0.6), Inches(0.55), Inches(0.9), Inches(0.09))  # rectangle
    bar.fill.solid(); bar.fill.fore_color.rgb = ACCENT
    bar.line.fill.background()
    bar.shadow.inherit = False
    # title
    tb = slide.shapes.add_textbox(Inches(0.6), Inches(0.85), Inches(12), Inches(1.2))
    p = tb.text_frame.paragraphs[0]
    p.text = title
    p.font.size = Pt(40 if is_title else 32)
    p.font.bold = True
    p.font.color.rgb = INK
    p.font.name = "Calibri"
    # bullets
    bb = slide.shapes.add_textbox(Inches(0.6), Inches(2.2), Inches(12), Inches(4.8))
    tf = bb.text_frame
    tf.word_wrap = True
    for i, item in enumerate(items):
        para = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        para.text = item
        para.font.size = Pt(18)
        para.font.color.rgb = BODY
        para.font.name = "Calibri"
        para.space_after = Pt(8)
        para.level = 0
    return slide

for i, t in enumerate(titles):
    if i == 0:
        add_slide(t, ["A minimal, professional deck."], True)
    elif i == 1:
        add_slide(t, agenda)
    elif i == N - 1:
        add_slide(t, ["Questions welcome."])
    else:
        add_slide(t, bullets.get(t, ["Illustrative editorial example."]))

prd.save(OUT)
print(f"saved {OUT} ({N} slides)")

# Verify: re-open and report.
check = Presentation(OUT)
print(f"verify: {len(check.slides)} slides")
for i, s in enumerate(check.slides, 1):
    title = ""
    for shape in s.shapes:
        if shape.has_text_frame and shape.text_frame.text.strip():
            title = shape.text_frame.text.strip().splitlines()[0][:60]
            break
    print(f"  {i}. {title}")
