"""SyncCamPhone user manual (PDF, ReportLab).

Usage: python docs/manual/annotate.py            (screenshots -> docs/manual/img)
       python docs/manual/make_manual.py         -> English manual
       python docs/manual/make_manual.py de      -> German (lang/de.json); also fr, nl, sl
       python docs/manual/make_manual.py all     -> all languages
       python docs/manual/make_manual.py extract -> lang/strings_en.json (all English strings)
Authors: Damir Vrancic and Claude
"""
import json
import sys
from pathlib import Path

from reportlab.graphics.shapes import Drawing, String, Circle
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (BaseDocTemplate, Frame, PageTemplate, Paragraph, Spacer, Table, TableStyle,
                                PageBreak, KeepTogether, NextPageTemplate, Image)
from reportlab.platypus.tableofcontents import TableOfContents

HERE = Path(__file__).resolve().parent
IMG = HERE / 'img'
OUT_BASE = 'SyncCamPhone_User_Manual_V0_105'
LANGS = ['de', 'fr', 'nl', 'sl']
TR = {}          # English -> translation of the current language
SEEN = []        # English strings in build order (extract mode)
MISSING = []
CUR = {'lang': 'en'}  # translations are longer: some pictures a bit smaller


def t(s):
    """Translate one text (the exact English string is the key)."""
    if not s:
        return s
    if s not in SEEN:
        SEEN.append(s)
    if not TR:
        return s
    v = TR.get(s)
    if v is None:
        MISSING.append(s)
        return s
    return v
FONTS = Path('C:/Windows/Fonts')
pdfmetrics.registerFont(TTFont('Body', str(FONTS / 'calibri.ttf')))
pdfmetrics.registerFont(TTFont('Body-B', str(FONTS / 'calibrib.ttf')))
pdfmetrics.registerFont(TTFont('Body-I', str(FONTS / 'calibrii.ttf')))
pdfmetrics.registerFontFamily('Body', normal='Body', bold='Body-B', italic='Body-I', boldItalic='Body-B')

VERSION = 'Version V0.105 – November 2026'
INK = colors.HexColor('#1F2933')
ACCENT = colors.HexColor('#1F5A96')
MUTED = colors.HexColor('#5F6B7A')
RULE = colors.HexColor('#C9D3DE')
NOTE_BG = colors.HexColor('#EEF3F8')
WARN_BG = colors.HexColor('#FDF1DE')
TEXT_W = 170 * mm

S = {
    'body': ParagraphStyle('body', fontName='Body', fontSize=10.5, leading=14.5, textColor=INK, spaceAfter=6),
    'h1': ParagraphStyle('h1', fontName='Body-B', fontSize=20, leading=24, textColor=ACCENT, spaceAfter=10),
    'h2': ParagraphStyle('h2', fontName='Body-B', fontSize=13.5, leading=17, textColor=ACCENT, spaceBefore=10,
                         spaceAfter=4, keepWithNext=1),
    'small': ParagraphStyle('small', fontName='Body-I', fontSize=9, leading=12, textColor=MUTED, spaceAfter=6),
    'cell': ParagraphStyle('cell', fontName='Body', fontSize=9.5, leading=12.5, textColor=INK),
    'cellb': ParagraphStyle('cellb', fontName='Body-B', fontSize=9.5, leading=12.5, textColor=INK),
    'bullet': ParagraphStyle('bullet', fontName='Body', fontSize=10.5, leading=14.5, textColor=INK,
                             leftIndent=14, bulletIndent=3, spaceAfter=3),
    'step': ParagraphStyle('step', fontName='Body', fontSize=10.5, leading=14.5, textColor=INK,
                           leftIndent=18, bulletIndent=2, spaceAfter=3),
    'title': ParagraphStyle('title', fontName='Body-B', fontSize=34, leading=40, textColor=ACCENT,
                            alignment=TA_CENTER),
    'subtitle': ParagraphStyle('subtitle', fontName='Body', fontSize=15, leading=20, textColor=INK,
                               alignment=TA_CENTER),
    'center': ParagraphStyle('center', fontName='Body', fontSize=11, leading=15, textColor=MUTED,
                             alignment=TA_CENTER),
    'toc1': ParagraphStyle('toc1', fontName='Body', fontSize=11.5, leading=19, textColor=INK, leftIndent=4),
}


# ---------------------------------------------------------------- building blocks
class H1(Paragraph):
    pass


def h1(text):
    return H1(t(text), S['h1'])


def P(text, st='body'):
    return Paragraph(t(text), S[st])


def bullets(items):
    return [Paragraph(t(x), S['bullet'], bulletText='\u2022') for x in items]


def steps(items):
    return [Paragraph(t(x), S['step'], bulletText='%d.' % (i + 1)) for i, x in enumerate(items)]


def box(flowables, bg=NOTE_BG, label=None):
    inner = ([Paragraph('<b>%s</b>' % t(label), S['body'])] if label else []) + flowables
    tb = Table([[inner]], colWidths=[TEXT_W])
    tb.setStyle(TableStyle([('BACKGROUND', (0, 0), (-1, -1), bg), ('LEFTPADDING', (0, 0), (-1, -1), 9),
                           ('RIGHTPADDING', (0, 0), (-1, -1), 9), ('TOPPADDING', (0, 0), (-1, -1), 7),
                           ('BOTTOMPADDING', (0, 0), (-1, -1), 4)]))
    return KeepTogether([Spacer(1, 2 * mm), tb, Spacer(1, 3 * mm)])


def tip(*paras):
    return box([P(x) for x in paras], label='Tip')


def warn(*paras):
    return box([P(x) for x in paras], bg=WARN_BG, label='Important')


def table(rows, widths, head=True, bold_first=True):
    data = [[Paragraph(t(str(c)), S['cellb' if (head and r == 0) or (bold_first and c_i == 0) else 'cell'])
             for c_i, c in enumerate(row)] for r, row in enumerate(rows)]
    tb = Table(data, colWidths=[w * mm for w in widths], repeatRows=1 if head else 0)
    st = [('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LINEBELOW', (0, 0), (-1, -1), 0.4, RULE),
          ('LEFTPADDING', (0, 0), (-1, -1), 5), ('RIGHTPADDING', (0, 0), (-1, -1), 5),
          ('TOPPADDING', (0, 0), (-1, -1), 3.5), ('BOTTOMPADDING', (0, 0), (-1, -1), 3.5)]
    if head:
        st += [('BACKGROUND', (0, 0), (-1, 0), NOTE_BG), ('LINEBELOW', (0, 0), (-1, 0), 0.8, ACCENT)]
    tb.setStyle(TableStyle(st))
    return tb


def img(name, width):
    path = IMG / (name + '.jpg')
    from PIL import Image as PI
    w, h = PI.open(path).size
    im = Image(str(path), width=width, height=width * h / w)
    im.hAlign = 'CENTER'
    return im


def pic(name, width=TEXT_W, caption=None, head=None):
    im = img(name, width)
    out = ([P(head, 'h2')] if head else []) + [Spacer(1, 1 * mm), im]
    if caption:
        out.append(Spacer(1, 1 * mm))
        out.append(P(caption, 'small'))
    return KeepTogether(out)


def legend(rows):
    return table([['', 'Part', 'What it does']] + rows, [8, 34, 128])


def button_colours():
    d = Drawing(TEXT_W, 70)
    items = [('#2E7D32', '1/1', 'all camera phones in sync'), ('#F9A825', '1/2', 'some in sync'),
             ('#D32F2F', '0/1', 'none in sync')]
    for i, (c, n, txt) in enumerate(items):
        x = 40 + i * 165
        d.add(Circle(x, 38, 25, fillColor=colors.HexColor(c), strokeColor=colors.white, strokeWidth=2.5))
        d.add(String(x, 41, '3D', fontName='Body-B', fontSize=11, fillColor=colors.white, textAnchor='middle'))
        d.add(String(x, 29, n, fontName='Body', fontSize=9, fillColor=colors.white, textAnchor='middle'))
        d.add(String(x + 33, 35, t(txt), fontName='Body', fontSize=10, fillColor=INK))
    return d


# ---------------------------------------------------------------- page templates
class Doc(BaseDocTemplate):
    def afterFlowable(self, f):
        if isinstance(f, H1):
            self.notify('TOCEntry', (0, f.getPlainText(), self.page))


def on_page(c, doc):
    c.saveState()
    c.setFont('Body', 8.5)
    c.setFillColor(MUTED)
    c.drawString(20 * mm, 12 * mm, t('SyncCamPhone V0.105 \u2013 User Manual'))
    c.drawRightString(190 * mm, 12 * mm, str(doc.page))
    c.setStrokeColor(RULE)
    c.setLineWidth(0.5)
    c.line(20 * mm, 15.5 * mm, 190 * mm, 15.5 * mm)
    c.restoreState()


def on_cover(c, doc):
    c.saveState()
    c.setFillColor(ACCENT)
    c.rect(0, A4[1] - 18 * mm, A4[0], 18 * mm, stroke=0, fill=1)
    c.rect(0, 0, A4[0], 10 * mm, stroke=0, fill=1)
    c.restoreState()


# ---------------------------------------------------------------- content
def cover():
    return [Spacer(1, 50 * mm), P('SyncCamPhone', 'title'), Spacer(1, 6 * mm),
            P('Synchronised stereo photos and videos with Android phones', 'subtitle'), Spacer(1, 4 * mm),
            P('User Manual', 'subtitle'), Spacer(1, 12 * mm),
            pic('master_photo', width=150 * mm), Spacer(1, 30 * mm),
            P('For Google Pixel 7a, Pixel 4 and Xreal Beam Pro', 'center'), P(VERSION, 'center'), Spacer(1, 4 * mm),
            P('Damir Vrancic and Claude', 'center'), NextPageTemplate('page'), PageBreak()]


def contents():
    toc = TableOfContents()
    toc.levelStyles = [S['toc1']]
    toc.dotsMinLevel = 0
    return [P('Contents', 'h1'), toc, PageBreak()]


def ch_intro():
    return [
        h1('1. Introduction'),
        P('SyncCamPhone turns two (or more) Android phones into one stereo camera. One phone is the '
          '<b>CONTROL</b> phone: you operate everything from it. The other phones are <b>camera phones</b>: they '
          'follow the CONTROL phone. All phones take each photo, and record each video frame, at the same moment. '
          'The left and right pictures can then be combined into a 3D photo or video with stereo software.'),
        P('The phones agree on a common clock over Wi-Fi. For video, every camera keeps its frames in step '
          'with the others. For photos, every phone uses the camera frame closest to the same moment. '
          'Typical differences between the phones are a few hundredths of a millisecond.'),
        P('What the app can do', 'h2'),
        *bullets(['<b>Stereo photos</b>: JPEG, RAW (DNG) or both, with the same exposure, white balance and '
                  'focus on all phones.',
                  '<b>HDR photos</b>: a short burst of frames merged into one photo with more detail in the '
                  'bright and dark parts. In dark scenes, extra frames are averaged automatically '
                  '(<b>Night</b>).',
                  '<b>Astro</b>: a series of long exposures (up to 16 s per frame) on a tripod for night skies '
                  'and night landscapes.',
                  '<b>Time-lapse</b>: synchronised stereo photos at a fixed interval.',
                  '<b>Stereo video</b> up to 4K at 30 frames per second, with sound.',
                  '<b>Lenses</b>: main, ultra-wide and front camera, chosen on the CONTROL phone.',
                  '<b>MONO</b>: the same photo and video modes on a single phone.',
                  '<b>Remote trigger</b> (experimental): presses the shutter of Google Camera or the record '
                  'button of Blackmagic Camera on all phones at the same moment.']),
        P('What you need', 'h2'),
        *bullets(['Two phones of the same model: <b>Google Pixel 7a</b> (recommended), <b>Pixel 4</b>, or '
                  '<b>Xreal Beam Pro</b> (its left lens). Other phones: the app finds their lenses by itself; '
                  '<b>Probe → Timing test</b> shows whether a phone can be synchronised.',
                  'A stereo bar or rig that holds both phones side by side, in landscape, at the same height.',
                  'For Astro and time-lapse: a stable tripod.',
                  'Wi-Fi: the CONTROL phone opens a hotspot and the camera phones connect to it. No internet is '
                  'needed.']),
        tip('Use two phones of the same model and the same app version (shown on the start screen). Different '
            'phone models have different lenses and timing and do not give good stereo pairs.'),
    ]


def ch_start():
    roles = [['Role', 'Use it for'],
             ['CONTROL + CAMERA', 'The phone you operate. It also takes the pictures of its own side. The usual '
                                  'choice for one phone of a stereo pair.'],
             ['CAMERA', 'The other phone(s). They follow the CONTROL phone; you do not need to touch them.'],
             ['CONTROL only', 'Operates the cameras without taking pictures itself (e.g. a third phone as a '
                              'remote control).'],
             ['MONO camera', 'One phone alone, without stereo. Files go to the Mono folder.'],
             ['Probe (camera tests)', 'Technical camera tests (e.g. the timing test for a new phone model).']]
    return [
        h1('2. Installation and first start'),
        P('Installing', 'h2'),
        P('The app is installed from a PC with USB debugging (developer options), or by opening the APK file on '
          'the phone (allow "install unknown apps"). Start <b>SyncCamPhone</b> on every phone and allow the camera, '
          'microphone and photos permissions when Android asks. An update installs over the old version and keeps '
          'all settings and permissions. Test versions up to V0.101 (CamSyncProbe) are a separate app: '
          'uninstall them.'),
        P('Connecting the phones', 'h2'),
        *steps(['On the <b>CONTROL phone</b>: turn on the Wi-Fi hotspot in Android settings.',
                'On every <b>camera phone</b>: connect to that hotspot.',
                'Start SyncCamPhone on all phones and choose the role (below).']),
        warn('All phones must be on the <b>same</b> Wi-Fi network. A camera phone on another network keeps '
             'switching its camera on and off (the picture flickers). Do not run a second CONTROL phone on the '
             'same network.'),
        tip('A house Wi-Fi also works, but the hotspot of the CONTROL phone gives the most stable clock sync. '
            'If another phone opens the hotspot, all messages pass through it and the sync gets less precise.'),
        pic('start_screen', head='The start screen', width=105 * mm,
            caption='The start screen shows the version and the lenses this phone has. It turns with the phone.'),
        table(roles, [42, 128]),
        Spacer(1, 3 * mm),
        Table([[img('position_dialog', 70 * mm),
                [P('After <b>CONTROL + CAMERA</b> or <b>CAMERA</b> the app asks for the <b>position of this '
                   'camera</b>: <b>LEFT</b> or <b>RIGHT</b>. Seen from behind the phones, the left phone writes '
                   'files ending in <b>-l</b>, the right phone files ending in <b>-r</b>. Stereo software pairs '
                   'the files by these endings.'),
                 P('If two phones choose the same side, a <b>red note</b> on the CONTROL phone and on those '
                   'cameras names them for a few seconds ("Both RIGHT: … the file names will not pair"). It '
                   'is only a warning: a third camera may share a side on purpose.')]]],
              colWidths=[75 * mm, 95 * mm],
              style=TableStyle([('VALIGN', (0, 0), (-1, -1), 'TOP'), ('LEFTPADDING', (0, 0), (-1, -1), 0)])),
        P('Once the roles are chosen, the cameras start by themselves. Wait until the status at the top left '
          'turns green (<b>SYNC</b>, see chapter 3). This takes about 20–40 s after the start.'),
    ]


def ch_main():
    master_rows = [
        ['1', 'Sync status', '<b>Green SYNC ±N µs</b>: all camera phones are connected and their clocks '
                             'agree within N microseconds. <b>Yellow</b>: in sync, frames still being aligned. '
                             '<b>Red SYNC …</b>: a camera phone is missing or not in sync yet. Then each camera '
                             'phone with its side (L / R).'],
        ['2', 'Standby (moon)', 'Switches all cameras off to save battery. Tap the CONTROL screen to wake them.'],
        ['3', 'Photo chip', 'Aspect ratio, size and format (here 16:9, 16 MP, JPEG; "Night" when dark scenes '
                            'are averaged). Tap it to open the photo settings.'],
        ['4', 'Lens', 'The lens (1× main, 0.5× ultra-wide, Front). Tap for the next lens (chapter 4).'],
        ['5', 'Exposure', 'Shutter time. Tap for a slider.'],
        ['6', 'ISO', 'Sensitivity. Tap for a slider.'],
        ['7', 'WB', 'White balance (here: shared and locked). Tap for a slider in Kelvin.'],
        ['8', 'Focus', 'Focus distance. Tap for a slider (or auto).'],
        ['9', 'AE', 'Measures the light and sets the same exposure on all phones, then shows the brightness '
                    'panel. Long press: only the panel (chapter 5.8).'],
        ['10', 'Image', 'The whole camera picture. Parts outside the chosen aspect ratio are darkened. '
                        'Tap a point to focus there.'],
        ['11', 'VIDEO / PHOTO', 'Switches between video and photo mode.'],
        ['12', 'Shutter', 'Takes a photo on all phones (<b>PHOTO #N</b> shows the number of the next photo). '
                          'In video mode it starts and stops the recording.'],
        ['13', 'Settings', 'Opens the settings (chapter 7).'],
    ]
    cam_rows = [
        ['1', 'Sync status', 'As on the CONTROL phone; <b>aligned</b> means the camera frames are in step.'],
        ['2', 'Chips', 'Mode, frame rate, exposure, white balance and focus received from the CONTROL phone.'],
        ['3', 'Lens', 'This camera\'s lens. Tap it to choose another lens for this phone only (chapter 4).'],
        ['4', 'Information', '<b>Battery</b>, <b>Free</b> memory, <b>≈ min video</b> that still fit, '
                             '<b>Phase</b> (frame timing error), <b>Clock</b> (clock error), <b>RTT</b> (Wi-Fi '
                             'round trip time).'],
        ['5', 'Settings', 'Information only; the camera phone is <b>controlled by CONTROL</b>.'],
    ]
    return [
        h1('3. The main screen'),
        P('Hold the phones in landscape. Everything is operated from the CONTROL phone; the camera phones show '
          'their own picture and status.'),
        pic('master_photo', head='3.1 CONTROL phone', width=TEXT_W if CUR['lang'] == 'en' else 150 * mm, caption='CONTROL phone in photo mode.'),
        legend(master_rows),
        PageBreak(),
        pic('camera_photo', head='3.2 Camera phone', width=140 * mm if CUR['lang'] == 'en' else 110 * mm, caption='Camera phone (follows the CONTROL phone).'),
        legend(cam_rows),
        P('3.3 Sliders', 'h2'),
        P('Tap the exposure, ISO, white balance or focus chip to get a slider. The new value goes to all phones '
          'when you lift the finger. Exposure and ISO change without restarting the cameras, so the sync stays '
          'green. White balance and focus sliders also have an <b>auto</b> button. The Back key closes the '
          'slider without a change.'),
        pic('master_slider', width=95 * mm if CUR['lang'] == 'en' else 75 * mm, caption='Exposure slider (video mode).'),
        P('3.4 Standby', 'h2'),
        P('After a time without use (120 s by default, see SYSTEM settings) or with the moon button, all cameras '
          'switch off. The CONTROL phone shows <b>tap to wake</b>, the camera phones <b>tap the CONTROL phone to '
          'wake</b>. After waking, the exposure is measured again automatically.'),
        warn('Keep SyncCamPhone in front on the CONTROL phone. If its screen goes off or another app is opened, '
             'it stops answering; the cameras reconnect when it is back, and the clock needs about 30 s to '
             'settle again.'),
    ]


def ch_lenses():
    rows = [['Lens', 'Pixel 7a', 'Remarks'],
            ['Main 1×', 'photo and video', 'Default; the app always starts with the main lens.'],
            ['Ultra-wide 0.5×', 'video only', 'In photo mode the phone keeps the main sensor: the app notices it, '
                                               'uses Main and shows a red note. Video works.'],
            ['Front', 'photo and video', 'Longest exposure 1 s (Astro uses 1 s frames).'],
            ['Telephoto', '–', 'Offered automatically on phones that have one (e.g. Pixel 4: 2×).']]
    return [
        h1('4. Lenses'),
        P('The app finds the lenses of each phone by itself and lists them on the start screen. Only lenses that '
          'can be synchronised are offered.'),
        *bullets(['<b>CONTROL phone</b>: Settings → PHOTO → <b>Lens</b>, or tap the lens chip (1×) to step '
                  'through the lenses. The choice is copied to all camera phones.',
                  '<b>Camera phone</b>: tap its own <b>Lens</b> chip to use another lens on this phone only, e.g. a '
                  'synchronised main + ultra-wide pair of the same scene. The next lens change on the CONTROL '
                  'phone sets all phones to its lens again.',
                  'When a camera\'s lens differs from the CONTROL phone\'s, a <b>red note</b> shows it for a few '
                  'seconds on both phones.']),
        table(rows, [30, 30, 110]),
        tip('For 3D pictures both phones must use the same lens. Different lenses are useful for non-3D work, '
            'for example a wide overview and a main-lens detail recorded at the same moment.'),
    ]


def ch_photo():
    fmt = [['Format', 'What you get'],
           ['JPEG', 'The camera\'s own JPEG photo from each phone (chapter 5.3).'],
           ['RAW', 'A DNG (raw) file from each phone, for your own processing.'],
           ['RAW+JPEG', 'Both.'],
           ['HDR', 'A burst: one normal frame plus up to 1, 2 or 3 longer frames for the dark parts, merged in '
                   'the phone into one JPEG and one 16-bit DNG. Dark scenes get extra averaged frames (Night).'],
           ['Astro', 'A series of long exposures on a tripod (chapter 5.5).']]
    return [
        h1('5. Taking photos'),
        P('5.1 Basic steps', 'h2'),
        *steps(['Mount the phones on the stereo bar and switch to <b>PHOTO</b>.',
                'Wait for the green <b>SYNC</b>.',
                'Tap <b>AE</b> to measure the light (or set exposure and ISO with the sliders).',
                'Tap the image to focus, or set the focus with the focus chip.',
                'Press the shutter. All phones take the photo at the same moment.']),
        P('Below the sync status the CONTROL phone shows the result of the last photo, e.g. <b>photo #12: -3 µs'
          '</b> (how far apart the frames of the phones were). The thumbnail under the shutter shows the last photo.'),
        P('5.2 Formats', 'h2'),
        P('Choose the format in <b>Settings → PHOTO → Format</b> (or tap the photo chip).'),
        table(fmt, [30, 140]),
        P('5.3 JPEG and RAW: shutter time and Night', 'h2'),
        P('JPEG, RAW and RAW+JPEG are single photos, processed by the camera itself (natural skin and '
          'detail). Three settings in <b>Settings → PHOTO</b> (shown only for these formats) decide how '
          'they are exposed:'),
        *bullets(['<b>Slowest shutter</b> (default <b>1/31 s</b>) and <b>Max ISO</b> (default <b>800</b>): the '
                  'limits of AE. For people who talk or move, 1/60 s or 1/125 s keeps them sharp.',
                  'When the light is short at these limits, AE says so in a yellow note (e.g. <b>Not enough '
                  'light: -1.3 EV at 1/31 s ISO 800</b>) and the exposure chip shows <b>-1.3 EV</b>. Brighten '
                  'the picture with the brightness slider that opens after AE, or with the ISO slider: both may '
                  'go beyond the limits, up to ISO 3200 (the next AE returns to the limits). Or raise Max ISO / Slowest shutter and press AE again.',
                  '<b>Night</b> (default <b>off</b>): when <b>on</b> and the light is short, the app takes '
                  'several frames and averages them; the photo chip shows <b>· Night</b> and the photo takes a '
                  'few seconds. Night is meant for still scenes: faces may look smeared.']),
        tip('Photograph people with JPEG and Night off: the camera\'s own processing renders faces much better '
            'than the app\'s merge.'),
        P('5.4 HDR and Night', 'h2'),
        P('HDR takes a short burst. The phones need to stay still for about a second, and moving objects may '
          'look blurred or be taken from one frame only. The merging takes a few seconds after the burst; the '
          'next photo can be taken when the shutter is ready again.'),
        P('<b>Extra frames (up to)</b> limits how much brighter the longest frame may be: 1 = mild (3 EV), '
          '3 = full range (9 EV). The HDR &amp; ASTRO tab sets the maximum ISO, the slowest shutter time, '
          '<b>Movement</b> (sharp or natural blur) and <b>Brightness</b> of the result (night look, natural, '
          'bright).'),
        tip('In very dark scenes, more frames are averaged (up to 15). Hold the phones as still as possible '
            'or use a tripod.'),
        pic('master_astro', head='5.5 Astro (tripod)',
            caption='Astro selected: the photo chip shows Astro (1), the exposure chip the planned frames (2), '
                    'the shutter ASTRO #N in yellow (3).'),
        *steps(['Put the phones on a <b>tripod</b>. They must not move during the whole series.',
                'Select <b>Format → Astro</b> and tap <b>AE</b>. The chips show the planned exposures.',
                'Press the shutter (<b>ASTRO #N</b>). The series starts on all phones at the same moment about '
                '4–5 s later, so that touching the phone does not shake the first frame.',
                'Do not touch the phones until the series has ended (1 or 2 minutes, see HDR &amp; ASTRO settings). '
                'During the series the shutter shows <b>STOP ASTRO</b>: press it to cancel.',
                'The phones merge the frames (some seconds) and save the photo.']),
        P('The final picture is made from the longest frames, like a real long exposure: lamps are white, and '
          'moving lights (cars, planes) leave trails. A series has at most 16 frames.'),
        warn('Astro is never remembered: when the app is started again, the format returns to HDR. This prevents '
             'starting a long series by mistake.'),
        P('5.6 Time-lapse', 'h2'),
        P('Settings → PHOTO → <b>Time-lapse</b>: <b>On</b>, the <b>interval</b> in seconds and the number of '
          '<b>frames</b> (0 = until stopped). The shutter then shows <b>TIME-LAPSE</b> (blue); press it to start, '
          'during the series it shows <b>STOP n/N</b>.'),
        *bullets(['Every shot is a normal synchronised photo in the chosen format (not Astro).',
                  'Focus and white balance are locked at the start on all phones.',
                  '<b>Time-lapse AE: locked at start</b> for constant light; <b>each shot (smoothed)</b> measures '
                  'about 3 s before every photo and changes the exposure by at most 1/3 EV per shot (e.g. a '
                  'sunrise without flicker).',
                  'Each series goes into its own folder <b>Timelapse_NNNN</b> (NNNN = number of its first photo) on '
                  'every phone. No standby during a series.']),
        tip('Choose an interval longer than one shot takes: JPEG about 2 s, HDR and Night bursts 8 s or more. '
            'Otherwise shots are delayed.'),
        P('5.7 MONO', 'h2'),
        P('Choose <b>MONO camera</b> on the start screen to use all photo and video modes on one phone. The status '
          'shows <b>● MONO</b>; files are saved in the <b>Mono</b> folder.'),
        P('5.8 Exposure (AE) and the brightness panel', 'h2'),
        P('The AE chip measures the scene once and gives all phones the same exposure. It does not keep changing '
          'it, so both pictures of a pair always match. Measure again when the light changes.'),
        pic('ae_panel', width=120 * mm,
            caption='Brightness panel: after pressing AE, or with a long press on AE (photo and video).'),
        *bullets(['<b>Reference brightness</b>: moves the measured exposure from -3 to +3 EV (above the frame time, '
                  'ISO is raised instead).',
                  '<b>Shutter</b> / <b>Clipped %</b>: chooses what the slider changes. <b>Clipped %</b> sets '
                  'how much of the picture may be overexposed (0 to 10 %) and measures again. Small values protect '
                  'bright parts (a lamp in the picture makes the rest darker); larger values give a brighter '
                  'picture where small lights may burn out.',
                  'The panel appears only when you press AE, not after the automatic measurement at the start or '
                  'after standby.'])]


def ch_video():
    return [
        h1('6. Video'),
        pic('master_video', caption='CONTROL phone in video mode.'),
        legend([['1', 'VIDEO / PHOTO', 'Video mode selected.'],
                ['2', 'Video chip', 'Resolution, frame rate (29.8 → 30 fps: see below) and bit rate. Tap '
                                    'it to open the video settings.'],
                ['3', 'REC', 'Starts the recording on all phones; press again to stop. The top bar shows '
                             '<b>● REC</b> and the recording time.']]),
        P('The phones record at 29.8 frames per second: slightly below 30, so that the app can keep the frames '
          'of all phones in step. With <b>Conform to 30 fps</b> (default on) the file is marked as 30 fps; it '
          'plays 0.7 % faster, which is not noticeable. Both files have exactly the same frames, so they can be '
          'put side by side without searching for the start.'),
        P('<b>Bitrate mode</b> (VIDEO settings): <b>VBR</b> is an average target - the encoder uses less in easy '
          'scenes and may use more in noisy ones. <b>CBR</b> holds the set bitrate (most detail in busy scenes, '
          'largest files). Each video\'s frame list records the mode used.'),
        P('Sound is recorded on every phone. The <b>Sound offset</b> (auto) corrects the delay of each phone\'s '
          'audio.'),
        tip('4K at 80 Mbit/s uses about 600 MB per minute. The camera phone shows how many minutes still fit '
            '(≈ min video).'),
    ]


def ch_settings():
    out = [h1('7. Settings'),
           P('Tap the gear on the CONTROL phone. The settings have five tabs. All values go to every phone; you '
             'never need to change anything on the camera phones. A tap beside the panel or the X at the top '
             'right closes it.')]
    out += [pic('set_photo', head='7.1 PHOTO', width=125 * mm),
            table([['Setting', 'Choices (default)', 'Meaning'],
                   ['Lens', '<b>Main 1×</b>, Ultra-wide 0.5×, Front', 'Chapter 4.'],
                   ['Aspect', '<b>4:3</b>, 3:2, 16:9, 1:1', 'Shape of the photo (a crop of the 4:3 sensor).'],
                   ['Format', '<b>JPEG</b>, RAW, RAW+JPEG, HDR, Astro', 'Chapter 5.2.'],
                   ['Extra frames (up to)', '1, 2, <b>3</b>', 'HDR only: number of longer frames.'],
                   ['Slowest shutter', '<b>1/31 s</b>, 1/60 s, 1/125 s', 'JPEG / RAW only: AE limit '
                                                                         '(chapter 5.3).'],
                   ['Max ISO', '<b>800</b>', 'JPEG / RAW only: AE limit; the sliders go up to 3200.'],
                   ['Night', '<b>off</b>, on', 'JPEG / RAW only: averaging when the light is short.'],
                   ['JPEG quality', '90, <b>95</b>, 100', '100 gives about 2× larger files.'],
                   ['Sharpening', 'Off, Low, <b>Normal</b>, Strong', 'Never used in Astro. Off also switches '
                                                                    'off the camera\'s own sharpening.'],
                   ['Local contrast', 'Off, Low, <b>Normal</b>, Strong', 'Crisper text and texture; HDR only.'],
                   ['AE: max clipped %', '<b>0.1</b>', 'How much of the picture the AE lets burn out.'],
                   ['Time-lapse', '<b>Off</b>, On; interval <b>10</b> s; frames <b>100</b>',
                    'Chapter 5.6.'],
                   ['Time-lapse AE', '<b>locked at start</b>, each shot (smoothed)', 'Chapter 5.6.']],
                  [34, 52, 84])]
    out += [PageBreak(), pic('set_photo2', head='Time-lapse section (PHOTO tab, scrolled down)', width=125 * mm)]
    out += [PageBreak(), pic('set_hdr2', head='7.2 HDR &amp; ASTRO', width=125 * mm),
            table([['Setting', 'Choices (default)', 'Meaning'],
                   ['Max ISO', '<b>800</b>', 'No frame uses a higher ISO (less noise, longer frames).'],
                   ['Slowest shutter', '<b>longest</b>, 1/60 s, 1/125 s', 'Longest exposure for HDR frames; '
                                                                         'shorter = less motion blur.'],
                   ['Movement', '<b>sharp</b>, natural blur', 'Natural blur uses ~1/31 s frames, like a video '
                                                              'frame.'],
                   ['Brightness', 'night look, <b>natural</b>, bright', 'How bright night photos look.'],
                   ['Duration (Astro)', '<b>1 min</b>, 2 min', 'Length of the astro series.'],
                   ['Light frame steps', '4 EV, <b>3 EV</b>, 1.5 EV', 'Steps between the short and the long '
                                                                     'astro frames.'],
                   ['Astro WB', '<b>neutral sky</b>, warm, daylight, auto (shared)', 'Colour of the night '
                                                                                    'sky.']],
                  [34, 52, 84])]
    out += [PageBreak(), pic('set_video', head='7.3 VIDEO', width=125 * mm),
            table([['Setting', 'Choices (default)', 'Meaning'],
                   ['Resolution', '1080p, <b>4K</b>', ''],
                   ['fps', '<b>29.8</b>', 'Frame rate (4K and photo mode: at most 29.9).'],
                   ['video Mbit/s', '<b>0 = auto</b> (max: what the phone allows)',
                    'Auto: 80 Mbit/s for 4K, 20 for 1080p.'],
                   ['Bitrate mode', '<b>VBR</b>, CBR', 'Chapter 6.'],
                   ['Codec', '<b>H.264</b>, HEVC', 'HEVC: smaller files at the same quality.'],
                   ['Conform to 30 fps', '<b>on</b>, off', 'Off keeps the real timing (29.8 fps).'],
                   ['Sound offset ms', '<b>auto</b>', 'Audio delay correction.']],
                  [34, 52, 84])]
    out += [pic('set_look', head='7.4 WB · TONE · FOCUS', width=125 * mm),
            table([['Setting', 'Choices (default)', 'Meaning'],
                   ['White balance', '<b>auto shared</b>, auto, K, daylight, cloudy, shade, incandescent, '
                                     'fluorescent, copy',
                    '<b>auto shared</b>: measured when you press AE and then the same on all phones (best for '
                    'stereo). <b>auto</b>: every phone on its own. <b>K</b>: a colour temperature (WB K, tint). '
                    '<b>copy</b>: the CONTROL phone\'s current values on all phones (press Lock WB).'],
                   ['Lock WB', 'button', 'Keeps the current white balance.'],
                   ['Calibrate WB', 'button', 'Measures the camera presets (3 s) so that Kelvin values are '
                                              'accurate. Done automatically the first time a K value is used.'],
                   ['Tone', '<b>standard</b>, soft, strong', 'Tone curve of the camera JPEG; soft keeps more '
                                                              'highlight detail.'],
                   ['Focus', '<b>auto</b>, manual', 'Manual: the distance in metres (Manual focus m).']],
                  [30, 52, 88])]
    out += [PageBreak(), pic('set_system', head='7.5 SYSTEM', width=125 * mm),
            table([['Setting', 'Choices (default)', 'Meaning'],
                   ['Standby after s', '<b>120</b> (min 10)', 'Time without use before the cameras switch off.'],
                   ['Photo JPEG', '<b>camera</b>, app (from YUV)', 'app: for phones whose camera JPEG disturbs '
                                                                    'the frame timing (Pixel 4). Phones where every '
                                                                    'camera JPEG does so (Beam Pro) use app JPEG '
                                                                    'by themselves.'],
                   ['Burst single DNGs', '<b>off</b>, save', 'Also saves every frame of an HDR / Astro burst '
                                                             '(about 300 MB per photo).'],
                   ['GPU merge', 'Off, <b>On</b>, Check', 'Merging on the graphics chip (several times '
                                                          'faster). Check: for testing only.'],
                   ['Anti-flicker', '<b>Off</b>, 50 Hz, 60 Hz', 'Against stripes under mains lamps: exposures of '
                                                               '10 ms (60 Hz: 8.3 ms) or longer become whole steps '
                                                               '(10, 20, 30 ms), the ISO keeps the brightness. Choose '
                                                               'the mains frequency of your country.'],
                   ['Remote trigger', 'button', 'Chapter 8.'],
                   ['Sync test (LED)', 'Frame alignment, LED capture', 'Technical timing tests. Keep Frame '
                                                                       'alignment <b>On</b>.'],
                   ['Info', '', 'Table of all phones, clock graph and log (useful when something does not '
                                'work).']],
                  [34, 52, 84])]
    return out


def ch_remote():
    return [
        h1('8. Remote trigger (experimental)'),
        P('With the remote trigger you can take stereo photos or videos with another camera app: <b>Google '
          'Camera</b> (e.g. Night Sight or Portrait mode) or <b>Blackmagic Camera</b> (its many video settings). '
          'SyncCamPhone switches its own cameras off but keeps the clock sync running in the background. A floating '
          '<b>3D</b> button on the CONTROL phone presses the other app\'s shutter or record button on all phones at '
          'the same moment.'),
        warn('The other app takes the photo or starts the video 0 to 2 frames (0 to about 70 ms) after the press, '
             'differently on each phone, and its video frames are not kept in step. Use the remote trigger for '
             '<b>still scenes</b> (landscapes, buildings); for moving subjects use SyncCamPhone\'s own modes.'),
        P('One-time setup', 'h2'),
        *steps(['On <b>every phone</b>: Android Settings → Apps → SyncCamPhone → three-dot menu '
                '→ <b>Allow restricted settings</b>.',
                'On every phone: Settings → Accessibility → SyncCamPhone → <b>on</b>. The app uses '
                'this only to press the button of the camera app while the remote trigger is running.',
                'On the <b>CONTROL phone</b>: allow <b>display over other apps</b> when the app asks (needed for the '
                'floating button).']),
        P('Use', 'h2'),
        *steps(['In SyncCamPhone on the CONTROL phone: Settings → SYSTEM → <b>Start remote trigger</b>.',
                'Open the camera app on all phones and choose the same mode and settings.',
                'Wait until the floating <b>3D</b> button is green, then tap it. All phones press the shutter (or '
                'record button) 0.4 s later at the same moment. Drag the 3D button away from the app\'s own '
                'buttons, so that it does not cover them.',
                'To stop: return to SyncCamPhone on the CONTROL phone and press <b>Stop remote trigger</b>. The '
                'normal cameras and the sync start again.']),
        P('Colour of the 3D button', 'h2'),
        button_colours(),
        P('The numbers show how many camera phones have a good clock sync, out of all camera phones (a stereo '
          'pair shows 1/1 or 0/1; yellow appears only with two or more camera phones). If the button is not '
          'green, wait a few seconds; tapping it anyway gives photos that may not be in sync.'),
    ]


def ch_files():
    return [
        h1('9. Files'),
        P('Every phone saves its own files in <b>Download/SyncCamPhone</b>. The number is the same on all phones '
          'for the same photo or video; the ending tells the side.'),
        table([['File', 'Content'],
               ['3D_img_0012-l.jpg', 'Photo #12 of the left phone (-r: right phone).'],
               ['3D_img_0012-l.dng', 'Raw photo, or the merged 16-bit DNG of an HDR / Astro photo.'],
               ['3D_vid_0003-l.mp4', 'Video #3 of the left phone.'],
               ['Timelapse_0120/3D_img_0120-l.jpg', 'Photos of a time-lapse series, in its own folder.'],
               ['Frames/3D_img_0012_frame0-l.dng', 'Single frames of a burst (only with Burst single DNGs, and '
                                                    'Astro).'],
               ['Info/3D_img_0012_info-l.txt', 'Technical information (exposures, timing, merge).'],
               ['Mono/2D_img_0005.jpg', 'Photos and videos of the MONO mode.']], [62, 108]),
        P('Copying to a PC', 'h2'),
        *steps(['Connect each phone with a USB cable and choose <b>File transfer</b> on the phone.',
                'Copy <b>Download/SyncCamPhone</b> from both phones into one folder on the PC.',
                'Stereo software pairs the left and right files by the same number and the -l / -r ending.']),
        tip('The numbering continues after the highest number found on any phone, also in the folder of earlier '
            'versions (Download/CamSyncProbe). If you delete all files on all phones, it starts again at 0001; '
            'copy the files to the PC first, otherwise new photos get the same numbers as old ones.'),
    ]


def ch_trouble():
    rows = [['Problem', 'What to do'],
            ['SYNC stays red', 'Check that all phones are on the same Wi-Fi (the CONTROL phone\'s hotspot) and '
                               'that SyncCamPhone is open on them. Wait 20–40 s after starting.'],
            ['The camera phone\'s picture flickers', 'It is on another Wi-Fi than the CONTROL phone: connect it to '
                                                     'the same network.'],
            ['SYNC is yellow', 'Normal for a few seconds after the start or after a mode change (frames are '
                               'being aligned).'],
            ['Sync lost after a while', 'The CONTROL phone\'s screen went off or another app was opened: return '
                                        'to SyncCamPhone; the cameras reconnect.'],
            ['Red note "Both LEFT / Both RIGHT"', 'Two phones chose the same side: restart one of them with the '
                                                  'other side (chapter 2).'],
            ['Red note "Different lens"', 'A camera phone uses another lens than the CONTROL phone (chapter 4).'],
            ['Red note "… not available in photo mode"', 'This lens does not work in photo mode on this '
                                                              'phone; Main is used. Use it in video mode.'],
            ['Pictures of the two phones differ in brightness or colour', 'Press AE again; use <b>auto '
                                                                          'shared</b> white balance.'],
            ['The photo takes long to appear', 'HDR, Night and Astro merge several frames: a few seconds '
                                               '(Astro longer). Keep <b>GPU merge On</b>.'],
            ['Astro photo is blurred', 'The phones moved: use a stable tripod, do not touch them during the '
                                       'series, avoid wind.'],
            ['Faces look smeared or waxy', 'Use JPEG with Night <b>off</b>; Slowest shutter 1/60 s or '
                                           '1/125 s for moving people (chapter 5.3).'],
            ['Note "Not enough light"', 'Brighten with the brightness or ISO slider, or raise Max ISO / Slowest '
                                        'shutter and press AE again (chapter 5.3).'],
            ['Dark areas are noisy', 'Use HDR or Astro instead of JPEG; increase Max ISO only if needed.'],
            ['The phone is full', 'Copy the files to a PC and delete them on the phone. Turn off <b>Burst single '
                                  'DNGs</b> (300 MB per photo).'],
            ['Cameras switched off', 'Standby: tap the CONTROL screen.'],
            ['The 3D button does not appear', 'Allow display over other apps for SyncCamPhone on the CONTROL phone.'],
            ['A new phone model does not sync', 'Run Probe → Timing test on it: it shows whether its camera can '
                                                'be kept in step.']]
    return [h1('10. Tips and troubleshooting'),
            P('Good stereo pictures', 'h2'),
            *bullets(['Keep the phones level and parallel; the lenses at the same height.',
                      'The lens distance of a typical bar (6–7 cm, like human eyes) suits subjects from about '
                      '2 m away. For distant landscapes a wider base gives more depth.',
                      'Avoid very close objects at the picture edges: they are hard to view in 3D.',
                      'For video, avoid fast panning: keep the rig steady or on a tripod.']),
            P('Problems', 'h2'),
            table(rows, [55, 115])]


def ch_appendix():
    rows = [['Tab', 'Setting', 'Default'],
            ['PHOTO', 'Lens / Aspect / Format / Extra frames', 'Main / 4:3 / JPEG / 3'],
            ['', 'JPEG quality / Sharpening / Local contrast', '95 / Normal / Normal'],
            ['', 'JPEG / RAW: Slowest shutter / Max ISO / Night', '1/31 s / 800 / off'],
            ['', 'AE: max clipped %', '0.1'],
            ['', 'Time-lapse / interval / frames / AE', 'Off / 10 s / 100 / locked at start'],
            ['HDR &amp; ASTRO', 'Max ISO / Slowest shutter / Movement', '800 / longest / sharp'],
            ['', 'Brightness', 'natural'],
            ['', 'Astro: Duration / Light frame steps / Astro WB', '1 min / 3 EV / neutral sky'],
            ['VIDEO', 'Resolution / fps / bit rate / mode', '4K / 29.8 / auto / VBR'],
            ['', 'Codec / Conform to 30 fps / Sound offset', 'H.264 / on / auto'],
            ['WB · TONE · FOCUS', 'White balance / Tone / Focus', 'auto shared / standard / auto'],
            ['SYSTEM', 'Standby after s / Photo JPEG', '120 / camera'],
            ['', 'Burst single DNGs / GPU merge / Anti-flicker / Frame alignment', 'off / On / Off / On']]
    return [h1('11. Appendix: default settings'), table(rows, [35, 80, 55], bold_first=True),
            Spacer(1, 8 * mm),
            Paragraph('SyncCamPhone – Damir Vrancic and Claude, ' + t(VERSION) + '.', S['small'])]


def build(lang):
    TR.clear()
    CUR['lang'] = lang
    del MISSING[:]
    out = HERE.parent / (OUT_BASE + ('' if lang == 'en' else '_' + lang.upper()) + '.pdf')
    if lang != 'en':
        TR.update(json.load(open(HERE / 'lang' / (lang + '.json'), encoding='utf-8')))
    doc = Doc(str(out), pagesize=A4, leftMargin=20 * mm, rightMargin=20 * mm, topMargin=18 * mm,
              bottomMargin=20 * mm, title=t('SyncCamPhone User Manual'), author='Damir Vrancic and Claude',
              subject=t('Synchronised stereo photos and videos with Android phones'), lang=lang)
    fr = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id='f')
    doc.addPageTemplates([PageTemplate('cover', [fr], onPage=on_cover, autoNextPageTemplate='page'),
                          PageTemplate('page', [fr], onPage=on_page, autoNextPageTemplate='page')])
    story = cover() + contents()
    for ch in (ch_intro, ch_start, ch_main, ch_lenses, ch_photo, ch_video, ch_settings, ch_remote, ch_files,
               ch_trouble, ch_appendix):
        story += ch() + [PageBreak()]
    story.pop()
    doc.multiBuild(story)
    if TR:  # untranslated strings stay English (names shown in the app are kept on purpose)
        print('  %d strings kept in English' % len(dict.fromkeys(MISSING)))
    print(out)


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else 'en'
    if arg == 'extract':
        build('en')
        (HERE / 'lang').mkdir(exist_ok=True)
        json.dump(SEEN, open(HERE / 'lang' / 'strings_en.json', 'w', encoding='utf-8'), ensure_ascii=False,
                  indent=0)
        print(len(SEEN), 'strings')
        return
    for lang in (['en'] + LANGS if arg == 'all' else [arg]):
        build(lang)


if __name__ == '__main__':
    main()
