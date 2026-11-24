"""Screenshots for the user manual: numbered markers (and crops) -> docs/manual/img/*.jpg.

Usage: python docs/manual/annotate.py
Authors: Damir Vrancic and Claude
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
SHOTS, OUT = HERE / 'shots', HERE / 'img'
OUT.mkdir(exist_ok=True)
FONT = ImageFont.truetype('C:/Windows/Fonts/calibrib.ttf', 44)
BLUE = (31, 90, 150)


def mark(im, pts):
    d = ImageDraw.Draw(im)
    for n, (x, y) in enumerate(pts, start=1):
        r = 34
        d.ellipse((x - r, y - r, x + r, y + r), fill=BLUE, outline=(255, 255, 255), width=5)
        d.text((x, y + 1), str(n), font=FONT, fill=(255, 255, 255), anchor='mm')
    return im


def save(name, im, crop=None, width=1600):
    im = im.convert('RGB')
    if crop:
        im = im.crop(crop)
    if im.width > width:
        im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
    im.save(OUT / (name + '.jpg'), quality=88)


def shot(n):
    return Image.open(SHOTS / (n + '.png')).convert('RGB')


# CONTROL phone, photo mode (V0.101: lens chip after the photo chip; Focus + AE in the second row)
save('master_photo', mark(shot('master_photo'), [
    (60, 75), (205, 455), (140, 765), (885, 765), (1070, 765), (1315, 765), (1630, 765), (100, 984),
    (650, 984), (1036, 300), (1900, 160), (2340, 440), (2176, 870)]))
# camera phone (its own lens chip at the end)
save('camera_photo', mark(shot('camera_photo'), [(60, 75), (140, 765), (1425, 984), (2360, 260), (2320, 1030)]))
# CONTROL phone, video mode
save('master_video', mark(shot('master_video'), [(1990, 75), (140, 765), (2340, 440)]))
save('master_slider', shot('master_slider'), crop=(0, 540, 2100, 1080))
save('ae_panel', shot('master_ae_panel'), crop=(700, 650, 1960, 1080))
save('master_astro', mark(shot('master_astro'), [(140, 765), (965, 765), (2176, 700)]))
# settings tabs: the panel only (right part of the screen)
for n in ['set_photo', 'set_photo2', 'set_hdr2', 'set_video', 'set_look', 'set_system']:
    save(n, shot(n), crop=(1064, 0, 2400, 1080), width=1100)
# start screen (landscape) and the position question
save('start_screen', shot('start_screen'), crop=(100, 150, 2300, 1080), width=1400)
save('position_dialog', shot('position_dialog'), crop=(520, 280, 1870, 870), width=900)
print('ok')
