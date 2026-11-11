import base64, io, json, os, tempfile
from pathlib import Path
import requests
from fastapi import FastAPI, Header, HTTPException, Request
from PIL import Image, ImageChops, ImageOps
from rembg import new_session, remove
from openai import OpenAI, BadRequestError

app=FastAPI(docs_url=None,redoc_url=None)
TOKEN=os.environ.get("AI_WORKER_TOKEN","")
IMAGE_MODEL=os.environ.get("OPENAI_IMAGE_MODEL","gpt-image-2.5-sunburst")
TEXT_MODEL=os.environ.get("OPENAI_TEXT_MODEL","gpt-5-mini")
GHOST_QUALITY=os.environ.get("OPENAI_GHOST_QUALITY","high")
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
    if max(image.size)>2048:
        image.thumbnail((2048,2048),Image.Resampling.LANCZOS)
    if _session is None:_session=new_session("isnet-general-use")
    return remove(image,session=_session,alpha_matting=True,alpha_matting_foreground_threshold=240,
        alpha_matting_background_threshold=10,alpha_matting_erode_size=8).convert("RGBA")

def ghost_mannequin(sources,garment_kind,feedback=""):
    if not os.environ.get("OPENAI_API_KEY"): raise ValueError("OPENAI_API_KEY es obligatoria para generar el maniquí fantasma")
    detail={"TOP":"interior neckline and natural shoulder, sleeve and torso volume","BOTTOM":"interior waistband and natural hip and leg volume","DRESS":"interior neckline and natural shoulder, waist and skirt volume"}[garment_kind]
    roles=", ".join(source.get("role","REFERENCE") for source in sources)
    prompt=("Create a professional isolated ecommerce product photograph of the supplied apparel. Input image order and roles: "+roles+". The first image is the approved transparent cutout; "
      "the remaining images are references for front, back and interior construction. Present the garment as a hollow three-dimensional retail form with "+detail+". Preserve the supplied garment's color, fabric, artwork, seams, labels, proportions and geometry. "
      "Use one centered front-facing garment on a fully transparent canvas. Do not add display equipment, scenery, floor, background, extra text or baked shadow. "
      "Do not invent details unsupported by the references. Requested correction: "+(feedback or "none"))
    files=[];paths=[]
    try:
        for index,source in enumerate(sources):
            image=download(source["imageUrl"]);path=Path(tempfile.gettempdir())/f"ghost-{os.getpid()}-{index}.png";image.save(path,"PNG");paths.append(path);files.append((path.name,path.read_bytes(),"image/png"))
        try: result=OpenAI().images.edit(model=IMAGE_MODEL,image=files,prompt=prompt,size="1600x2000",quality=GHOST_QUALITY,background="transparent",output_format="png",moderation="low")
        except BadRequestError as error:
            if getattr(error,"code",None)!="moderation_blocked": raise
            retry=("Produce a clean isolated apparel catalog image from these product references. Give the garment natural three-dimensional volume and a visible hollow interior opening. "
              "Keep the supplied colors, construction and artwork unchanged. Center one front-facing garment on transparency; add no scenery, equipment, extra writing or shadow.")
            result=OpenAI().images.edit(model=IMAGE_MODEL,image=files,prompt=retry,size="1600x2000",quality=GHOST_QUALITY,background="transparent",output_format="png",moderation="low")
        image=Image.open(io.BytesIO(base64.b64decode(result.data[0].b64_json))).convert("RGBA");alpha=image.getchannel("A")
        if not alpha.getbbox() or alpha.getextrema()[0]>=250: raise ValueError("el maniquí generado no contiene transparencia real")
        return image
    finally:
        for path in paths:path.unlink(missing_ok=True)

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

def validate_cover(data,size):
    if not data: raise ValueError("la portada generada está vacía")
    with Image.open(io.BytesIO(data)) as generated:
        generated.load()
        if generated.format!="WEBP": raise ValueError("la portada generada no tiene formato WebP")
        if generated.size!=size: raise ValueError(f"la portada generada debe medir {size[0]}x{size[1]}")
        if "A" in generated.getbands() and generated.getchannel("A").getextrema()[0]<255:
            raise ValueError("la portada generada debe ser completamente opaca")
        image=generated.convert("RGB")
    solid=Image.new("RGB",size,image.getpixel((0,0)))
    if ImageChops.difference(image,solid).getbbox() is None: raise ValueError("la portada generada no contiene una composición visible")
    return image

def generate_cover(sources,style,size,feedback="",desktop=None):
    prompt=(f"Create a premium Blessrom fashion ecommerce campaign in the {style} direction using every supplied approved garment and no other apparel. "
      "Make the garments the dominant focal subject: together they should occupy approximately 45 to 65 percent of the usable canvas. Show every garment fully, sharply and at high visual contrast, "
      "with no cropping, overlap, person, mannequin, prop or scenery obscuring any part. Preserve the exact supplied colors, artwork, fabric appearance, seams, proportions and geometry. "
      f"Compose exactly for {size[0]}x{size[1]} and reserve a clean safe area for separate HTML copy. Do not render text, logos, watermarks, prices, discounts or invented garments. "
      f"Administrator correction: {feedback or 'none'}.")
    if desktop is not None:
        prompt+=(" The first reference is the approved desktop campaign and is provided only to retain its art direction. "
          "Create an independent mobile composition from the subsequent garment references; keep all garments prominent and fully visible rather than merely cropping the desktop image.")
    files=[];paths=[]
    try:
        for index,source in enumerate(sources):
            image=download(source["imageUrl"]);path=Path(tempfile.gettempdir())/f"blessrom-source-{os.getpid()}-{index}.png";image.save(path,"PNG");paths.append(path);files.append((path.name,path.read_bytes(),"image/png"))
        if desktop is not None:
            path=Path(tempfile.gettempdir())/f"blessrom-desktop-{os.getpid()}.webp";desktop.save(path,"WEBP");paths.append(path);files.insert(0,(path.name,path.read_bytes(),"image/webp"))
        result=OpenAI().images.edit(model=IMAGE_MODEL,image=files,prompt=prompt,size=f"{size[0]}x{size[1]}",quality="medium",output_format="webp",background="opaque")
        encoded=result.data[0].b64_json if result.data else None
        return validate_cover(base64.b64decode(encoded) if encoded else b"",size)
    finally:
        for path in paths:path.unlink(missing_ok=True)

@app.get("/health")
def health(): return {"status":"UP","model":IMAGE_MODEL}

@app.post("/suggest-product")
async def suggest_product(http_request:Request,authorization:str|None=Header(default=None)):
    auth(authorization)
    work=await http_request.json()
    image=download(work["imageUrl"])
    image.thumbnail((1024,1024),Image.Resampling.LANCZOS)
    image=image.convert("RGB")
    encoded=io.BytesIO();image.save(encoded,"JPEG",quality=85)
    categories=work.get("categories",[]);colors=work.get("colors",[])
    prompt=("Analiza esta fotografía de una prenda para preparar un borrador de catálogo Blessrom. "
      "Devuelve solo JSON con name, description, categoryId y color. "
      "Usa categoryId únicamente de esta lista: "+json.dumps(categories,ensure_ascii=False)+". "
      "Usa color únicamente de esta lista de valores internos: "+json.dumps(colors,ensure_ascii=False)+". "
      "Si no puedes determinar categoría o color, usa cadena vacía. No infieras talla, precio, marca, composición, stock ni atributos invisibles. "
      "Describe solo rasgos visibles y evita afirmaciones no verificables. Nombre máximo 180 caracteres, descripción máximo 5000.")
    response=OpenAI().responses.create(model=TEXT_MODEL,input=[{"role":"user","content":[
      {"type":"input_text","text":prompt},
      {"type":"input_image","image_url":"data:image/jpeg;base64,"+base64.b64encode(encoded.getvalue()).decode("ascii")}
    ]}])
    raw=response.output_text.strip();start=raw.find("{");end=raw.rfind("}")+1
    if start<0 or end<=start: raise HTTPException(502,"El asistente no devolvió sugerencias válidas")
    value=json.loads(raw[start:end])
    allowed_categories={str(item["id"]) for item in categories};allowed_colors={str(item["value"]) for item in colors}
    return {"name":str(value.get("name", ""))[:180],"description":str(value.get("description", ""))[:5000],
      "categoryId":str(value.get("categoryId", "")) if str(value.get("categoryId", "")) in allowed_categories else "",
      "color":str(value.get("color", "")) if str(value.get("color", "")) in allowed_colors else ""}

@app.post("/process")
async def process(http_request:Request,authorization:str|None=Header(default=None)):
    auth(authorization)
    try: work=await http_request.json()
    except Exception as error: raise HTTPException(400,"cuerpo JSON inválido") from error
    if not isinstance(work,dict): raise HTTPException(400,"el trabajo debe ser un objeto JSON")
    job_type=work["jobType"];request=work["request"]
    if job_type=="CUTOUT":
        if request.get("stage")=="GHOST": result=ghost_mannequin(work.get("sources",[]),request["garmentKind"],request.get("feedback",""))
        else: result=cutout(download(work["sourceUrl"]),request.get("feedback",""))
        prepared=variants(result,"product")
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
