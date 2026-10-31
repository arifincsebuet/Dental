"""Raster exports of the vector-native brand, without an external SVG renderer."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).parent
SCALE = 24

def bezier(start, c1, c2, end, steps=60):
    return [tuple((1-t)**3*start[k] + 3*(1-t)**2*t*c1[k] + 3*(1-t)*t*t*c2[k] + t**3*end[k] for k in (0,1)) for t in [i/steps for i in range(steps+1)]]

def icon(size):
    image = Image.new("RGBA", (108*SCALE,108*SCALE))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((0,0,108*SCALE-1,108*SCALE-1),26*SCALE,fill="#0F766E")
    points=[(38,30)]
    curves=[((30,30),(29,40),(32,49)),((35,57),(35,76),(41,79)),((46,82),(46,61),(54,61)),((62,61),(62,82),(67,79)),((73,76),(73,57),(76,49)),((79,40),(78,30),(70,30)),((64,29),(60,34),(54,34)),((48,34),(44,29),(38,30))]
    for c1,c2,end in curves: points.extend(bezier(points[-1],c1,c2,end)[1:])
    draw.polygon([(round(x*SCALE),round(y*SCALE)) for x,y in points],fill="white")
    highlight=bezier((39,38),(42,36),(46,39),(49,40))
    draw.line([(round(x*SCALE),round(y*SCALE)) for x,y in highlight], fill="#99F6E4",width=round(2.5*SCALE),joint="curve")
    for x,y in [highlight[0],highlight[-1]]: draw.ellipse(((x-1.25)*SCALE,(y-1.25)*SCALE,(x+1.25)*SCALE,(y+1.25)*SCALE),fill="#99F6E4")
    spark=[(77,23),(79.2,29.8),(86,32),(79.2,34.2),(77,41),(74.8,34.2),(68,32),(74.8,29.8)]
    polygon=[(round(x*SCALE),round(y*SCALE)) for x,y in spark]
    draw.polygon(polygon,fill="#2563EB")
    draw.line(polygon+[polygon[0]],fill="#CCFBF1",width=round(.7*SCALE),joint="curve")
    return image.resize((size,size),Image.Resampling.LANCZOS)

icon(1024).save(ROOT/"dental-care-logo.png",optimize=True)
icon(512).save(ROOT/"dental-care-logo-512.png",optimize=True)
canvas=Image.new("RGB",(1600,720),"#F0FDFA")
logo=icon(280)
canvas.paste(logo,(130,220),logo)
draw=ImageDraw.Draw(canvas)
font=ImageFont.truetype(r"C:\Windows\Fonts\segoeuib.ttf",104)
body=ImageFont.truetype(r"C:\Windows\Fonts\segoeui.ttf",40)
draw.text((475,238),"Dental Care",fill="#1E293B",font=font)
draw.text((482,380),"Clear records. Confident care.",fill="#0F766E",font=body)
canvas.save(ROOT/"dental-care-brand.png",optimize=True)
for target in ROOT.glob("*.png"):
    print(target.name,target.stat().st_size,"bytes")
