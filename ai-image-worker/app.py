import base64, io, json, os, tempfile
from pathlib import Path
import requests
from fastapi import FastAPI, Header, HTTPException
from PIL import Image, ImageOps
from rembg import new_session, remove
from openai import OpenAI

app=FastAPI(docs_url=None,redoc_url=None)
TOKEN=os.environ.get("AI_WORKER_TOKEN","")
IMAGE_MODEL=os.environ.get("OPENAI_IMAGE_MODEL","gpt-image-2.5-sunburst")
TEXT_MODEL=os.environ.get("OPENAI_TEXT_MODEL","gpt-5-mini")
_session=None

def auth(header):
    if not TOKEN or header!=f"Bearer {TOKEN}": raise HTTPException(401,"worker token inválido")

def download(url):
    response=requests.get(url,timeout=60);response.raise_for_status()
    if len(response.content)>25*1024*1024: raise ValueError("imagen demasiado grande")
    image=Image.open(io.BytesIO(response.content));image.load();return ImageOps.exif_transpose(image).convert("RGBA")

def cutout(image,feedback=""):
    global _session
    if feedback and os.environ.get("OPENAI_API_KEY"):
        with tempfile.NamedTemporaryFile(suffix=".png") as source:
            image.save(source.name,"PNG")
            result=OpenAI().images.edit(model=IMAGE_MODEL,image=[open(source.name,"rb")],prompt=(
                "Extract only the garment onto a fully transparent background. Preserve exact color, geometry, seams, labels and fabric. "
                "No scenery, mannequin, person, checkerboard or invented details. Requested correction: "+feedback),
                size="1024x1536",quality="medium",background="transparent",output_format="png")
            return Image.open(io.BytesIO(base64.b64decode(result.data[0].b64_json))).convert("RGBA")
    if _session is None:_session=new_session("isnet-general-use")
    return remove(image,session=_session,alpha_matting=True,alpha_matting_foreground_threshold=240,
        alpha_matting_background_threshold=10,alpha_matting_erode_size=8).convert("RGBA")

def normalize(image,size):
    alpha=image.getchannel("A");box=alpha.getbbox()
    if not box: raise ValueError("el recorte no contiene una prenda visible")
    subject=image.crop(box);target_w,target_h=size
    scale=min(target_w*.84/subject.width,target_h*.88/subject.height)
    if scale<=0: raise ValueError("dimensiones inválidas")
    subject=subject.resize((max(1,round(subject.width*scale)),max(1,round(subject.height*scale))),Image.Resampling.LANCZOS)
    canvas=Image.new("RGBA",size,(0,0,0,0));canvas.alpha_composite(subject,((target_w-subject.width)//2,(target_h-subject.height)//2));return canvas

def encode_webp(image,quality):
    out=io.BytesIO();image.save(out,"WEBP",quality=quality,method=6,lossless=False);return out.getvalue()

def resize_exact(image,size):
    return ImageOps.fit(image.convert("RGB"),size,method=Image.Resampling.LANCZOS,centering=(.5,.5))

def variants(image,kind):
    specs={"product":{"original":((1600,2000),92),"large":((1200,1500),88),"medium":((720,900),84),"thumbnail":((320,400),80)},
      "desktop":{"original":((2160,720),90),"large":((1200,400),86),"medium":((720,240),82),"thumbnail":((320,107),78)},
      "mobile":{"original":((1024,1280),90),"large":((960,1200),86),"medium":((720,900),82),"thumbnail":((320,400),78)}}[kind]
    result={}
    for name,(size,quality) in specs.items():
        current=normalize(image,size) if kind=="product" else resize_exact(image,size)
        result[name]=(current,encode_webp(current,quality))
    return result

def upload_variants(prepared,targets):
    metadata=[]
    for target in targets:
        image,data=prepared[target["variant"]]
        response=requests.put(target["uploadUrl"],data=data,headers={"Content-Type":"image/webp"},timeout=120);response.raise_for_status()
        metadata.append({"variant":target["variant"],"width":image.width,"height":image.height,"byteSize":len(data)})
    return metadata

def json_copy(style,sources,feedback=""):
    names="; ".join(f"{source.get('name',source.get('altText','prenda Blessrom'))}: {source.get('description','')}" for source in sources)
    prompt=f'''Redacta una portada comercial para Blessrom basada únicamente en estas prendas y descripciones verificadas: {names}.
Estilo visual: {style}. Observación del administrador: {feedback or "ninguna"}.
No inventes precios, descuentos, materiales, stock ni beneficios. Devuelve solo JSON válido con eyebrow (máx 45), title (máx 80), description (máx 180) y buttonLabel (máx 35). El botón dirige al catálogo.'''
    response=OpenAI().responses.create(model=TEXT_MODEL,input=prompt)
    text=response.output_text.strip();start=text.find("{");end=text.rfind("}")+1
    value=json.loads(text[start:end]);value["target"]="catalog";return value

def generate_cover(sources,style,size,feedback="",desktop=None):
    prompt=(f"Create a premium Blessrom fashion ecommerce campaign in the {style} direction. Use only the supplied garments, preserve their exact colors, seams and geometry. "
      f"Composition {size}; leave safe negative space for HTML copy. No text, logo, watermark, prices or invented garments. {feedback}")
    files=[]
    try:
        for index,source in enumerate(sources):
            image=download(source["imageUrl"]);path=Path(tempfile.gettempdir())/f"blessrom-source-{os.getpid()}-{index}.png";image.save(path,"PNG");files.append(open(path,"rb"))
        if desktop is not None:
            path=Path(tempfile.gettempdir())/f"blessrom-desktop-{os.getpid()}.webp";desktop.save(path,"WEBP");files.insert(0,open(path,"rb"))
        result=OpenAI().images.edit(model=IMAGE_MODEL,image=files,prompt=prompt,size=f"{size[0]}x{size[1]}",quality="medium",output_format="webp",background="opaque")
        return Image.open(io.BytesIO(base64.b64decode(result.data[0].b64_json))).convert("RGB")
    finally:
        for file in files:file.close()

@app.get("/health")
def health(): return {"status":"UP","model":IMAGE_MODEL}

@app.post("/process")
def process(work:dict,authorization:str|None=Header(default=None)):
    auth(authorization);job_type=work["jobType"];request=work["request"]
    if job_type=="CUTOUT":
        original=download(work["sourceUrl"]);result=cutout(original,request.get("feedback",""));prepared=variants(result,"product")
        return {"results":[{"slot":0,"content":{},"desktopObjects":upload_variants(prepared,work["outputs"]),"mobileObjects":None}]}
    sources=request.get("sources",[]);styles=["dark tropical editorial inspired by Tarapoto","warm ivory architectural studio","refined tropical overhead flat lay"]
    target=request.get("targetSlot");scope=request.get("scope","");feedback=request.get("feedback","");results=[]
    for output in work.get("outputs",[]):
        slot=int(output["slot"]);style=styles[slot];content=json_copy(style,sources,feedback) if scope in ("","COPY","BOTH") else None
        desktop_objects=mobile_objects=None
        if scope!="COPY":
            desktop=generate_cover(sources,style,(2160,720),feedback);mobile=generate_cover(sources,style,(1024,1280),feedback,desktop)
            desktop_objects=upload_variants(variants(desktop,"desktop"),output["desktop"]);mobile_objects=upload_variants(variants(mobile,"mobile"),output["mobile"])
        results.append({"slot":slot,"content":content or {},"desktopObjects":desktop_objects,"mobileObjects":mobile_objects})
    return {"results":results}
