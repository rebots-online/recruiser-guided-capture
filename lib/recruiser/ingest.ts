export const MEDIA_EXTENSIONS=['jpg','jpeg','png','webp','heic','heif','avif','mp4','mov','webm','insv','insp'];
export const extension=(name:string)=>name.toLowerCase().split('.').pop()??'';
export type Projection='perspective'|'equirectangular'|'dual-fisheye'|'unknown';
export async function prepareMediaPreview(blob:Blob,name:string):Promise<{blob:Blob;mime:string;kind:'image'|'video'|'unsupported'}>{
 const head=new Uint8Array(await blob.slice(0,16).arrayBuffer());
 const jpeg=head[0]===255&&head[1]===216&&head[2]===255;
 const mp4=new TextDecoder().decode(head.slice(4,8))==='ftyp';
 const ext=extension(name),raw=ext==='insp'||ext==='insv';
 const types:Record<string,string>={jpg:'image/jpeg',jpeg:'image/jpeg',png:'image/png',webp:'image/webp',heic:'image/heic',heif:'image/heif',avif:'image/avif',mp4:'video/mp4',mov:'video/quicktime',webm:'video/webm'};
 // HEIF/AVIF also contain ftyp: preserve their image container identity.
 const imageContainer=['heic','heif','avif'].includes(ext);
 const mime=jpeg?'image/jpeg':mp4&&!imageContainer?'video/mp4':raw?'application/octet-stream':types[ext]||blob.type;
 const kind=mime.startsWith('image/')?'image':mime.startsWith('video/')?'video':'unsupported';
 return{blob:blob.slice(0,blob.size,mime),mime,kind};
}
export async function inspectMediaProjection(blob:Blob,name:string):Promise<Projection>{
 if(extension(name)!=='insp')return extension(name)==='insv'?'unknown':'perspective';
 // Camera trailer only. Never infer a panorama from image dimensions or expose GPS.
 const tail=new TextDecoder().decode(await blob.slice(Math.max(0,blob.size-4096)).arrayBuffer());
 const end=tail.lastIndexOf('}');
 for(let start=tail.indexOf('{');start>=0&&start<end;start=tail.indexOf('{',start+1)){
  try{const metadata=JSON.parse(tail.slice(start,end+1)),info=metadata?.info??metadata;if(info?.cameraType==='air'&&info?.exportTime===0)return'dual-fisheye'}catch{}
 }
 return'unknown';
}
export async function inspectMedia(file:File){
 const ext=extension(file.name);if(!MEDIA_EXTENSIONS.includes(ext))throw Error('Unsupported capture format: '+ext);
 const raw=['insv','insp'].includes(ext),preview=await prepareMediaPreview(file,file.name);
 let previewUrl:string|null=null;
 if(preview.kind!=='unsupported'&&typeof document!=='undefined'){
  const url=URL.createObjectURL(preview.blob);
  try{await new Promise<void>((resolve,reject)=>{
   const isVideo=preview.kind==='video',el=isVideo?document.createElement('video'):new Image();
   const event=isVideo?'loadeddata':'load';let timer:ReturnType<typeof setTimeout>;
   const finish=(error=false)=>{clearTimeout(timer);el.removeEventListener(event,ready);el.removeEventListener('error',failed);el.removeAttribute('src');if(isVideo)(el as HTMLVideoElement).load();error?reject(Error('Preview unavailable')):resolve()};
   const ready=()=>finish(isVideo&&!(el as HTMLVideoElement).videoWidth),failed=()=>finish(true);
   el.addEventListener(event,ready);el.addEventListener('error',failed);timer=setTimeout(failed,8000);
   if(isVideo){(el as HTMLVideoElement).preload='auto';(el as HTMLVideoElement).muted=true}
   el.src=url;
  });previewUrl=url}catch{URL.revokeObjectURL(url)}
 }
 return{name:file.name,byteLength:file.size,mime:file.type||'application/octet-stream',projection:await inspectMediaProjection(file,file.name),captureTime:null as string|null,previewUrl,requiresWorker:raw||!previewUrl};
}
