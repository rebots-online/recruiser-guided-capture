'use client';
import {useEffect,useState} from 'react';
import {Film,Camera,Plus,Expand,Trash2,ChevronRight} from 'lucide-react';
import {Checkbox} from '@/components/ui/checkbox';
import {Dialog,DialogContent,DialogHeader,DialogTitle,DialogDescription} from '@/components/ui/dialog';
import CapturePreview from './CapturePreview';
import {Choice} from './WorkerSettings';
import type {Source} from '@/lib/recruiser/storage';
import {toast} from 'sonner';
import {openStore} from '@/lib/recruiser/storage';
import {prepareMediaPreview} from '@/lib/recruiser/ingest';
const video=(name:string)=>/\.(insv|mp4|webm|mov)$/i.test(name);
export const formatBytes=(b:number)=>b>=1024**3?(b/1024**3).toFixed(2)+' GB':b>=1024**2?(b/1024**2).toFixed(1)+' MB':(b/1024).toFixed(1)+' KB';
// One native decode at a time: camera photos can each consume tens of MB.
let thumbnailQueue:Promise<void>=Promise.resolve();
function Thumb({source}:{source:Source}){
 const[url,setUrl]=useState(''),[failed,setFailed]=useState(false);
 useEffect(()=>{
  let dead=false,u='',cancelDecode=()=>{};setUrl('');setFailed(false);
  const task=async()=>{
   if(dead)return;
   const blob=await(await openStore()).getBlob(source.previewBlobKey||source.blobKey);
   const preview=await prepareMediaPreview(blob,source.previewName||source.name);
   if(dead)return;if(preview.kind==='unsupported')throw Error('Preview unavailable');
   const isVideo=preview.kind==='video',el=isVideo?document.createElement('video'):new Image();
   const originalUrl=URL.createObjectURL(preview.blob);
   try{
    await new Promise<void>((resolve,reject)=>{
     const event=isVideo?'loadeddata':'load';
     const finish=(error=false)=>{clearTimeout(timer);el.removeEventListener(event,ready);el.removeEventListener('error',errorHandler);error?reject(Error('Preview unavailable')):resolve()};
     const ready=()=>finish(),errorHandler=()=>finish(true),timer=setTimeout(errorHandler,8000);
     cancelDecode=errorHandler;el.addEventListener(event,ready);el.addEventListener('error',errorHandler);
     if(isVideo){(el as HTMLVideoElement).muted=true;(el as HTMLVideoElement).preload='auto'}
     el.src=originalUrl;
    });
    if(dead)return;
    const width=isVideo?(el as HTMLVideoElement).videoWidth:(el as HTMLImageElement).naturalWidth;
    const height=isVideo?(el as HTMLVideoElement).videoHeight:(el as HTMLImageElement).naturalHeight;
    if(!width||!height)throw Error('No picture');
    const canvas=document.createElement('canvas'),scale=Math.min(1,480/Math.max(width,height));
    canvas.width=Math.max(1,Math.round(width*scale));canvas.height=Math.max(1,Math.round(height*scale));
    const context=canvas.getContext('2d');if(!context)throw Error('Preview unavailable');
    context.drawImage(el,0,0,canvas.width,canvas.height);
    try{const thumbnail=await new Promise<Blob|null>(resolve=>canvas.toBlob(resolve,'image/jpeg',.82));
     if(!dead&&thumbnail){u=URL.createObjectURL(thumbnail);setUrl(u)}
     else if(!dead)throw Error('Preview unavailable');
    }finally{canvas.width=0;canvas.height=0}
   }finally{cancelDecode=()=>{};el.removeAttribute('src');if(isVideo)(el as HTMLVideoElement).load();URL.revokeObjectURL(originalUrl)}
  };
  thumbnailQueue=thumbnailQueue.then(task).catch(()=>{if(!dead)setFailed(true)});
  return()=>{dead=true;cancelDecode();if(u)URL.revokeObjectURL(u)};
 },[source.blobKey,source.name,source.previewBlobKey,source.previewName,source.previewMime]);
 return url&&!failed?<img src={url} alt="" onError={()=>setFailed(true)}/>:<div className="thumb-fallback">{video(source.name)?<Film size={25}/>:<Camera size={25}/>}<span>{failed?'Preview unavailable':'Loading preview…'}</span></div>;
}
export default function SourceQueue({packageSources,onReviewPackage,onCaptureSurroundings,sources,selected,onSelect,onSelectAll,onUpdate,onRemove,onAdd,busy,nextAction}:{packageSources:Source[];onReviewPackage:(source:Source)=>void;onCaptureSurroundings:()=>void;sources:Source[];selected:Set<string>;onSelect:(id:string,v:boolean)=>void;onSelectAll:(value:boolean)=>void;onUpdate:(s:Source)=>void;onRemove:(id:string)=>void;onAdd:()=>void;busy:boolean;nextAction:{label:string;hint:string;disabled:boolean;onClick:()=>void}}){const[detail,setDetail]=useState<Source|null>(null);const selectedCount=sources.filter(s=>selected.has(s.id)).length;
 return <><div className="source-tray"><div className="capture-next"><div className="capture-next-copy"><strong>{sources.length?`${selectedCount} of ${sources.length} captures selected`:'Start with your captures'}</strong><p id="capture-next-hint" aria-live="polite">{nextAction.hint}</p></div><button className="primary capture-next-button" aria-describedby="capture-next-hint" disabled={nextAction.disabled} onClick={nextAction.onClick}>{nextAction.label}<ChevronRight size={18}/></button></div><div className="tray-heading"><div><span className="section-label">CAPTURE SOURCES</span><span className="count">{sources.length.toString().padStart(2,'0')}</span></div><div className="tray-selection"><button className="text-button" onClick={onCaptureSurroundings} disabled={busy}><Camera size={16}/> Capture surroundings</button>{sources.length>0&&<button className="text-button" onClick={()=>onSelectAll(selectedCount!==sources.length)} disabled={busy}>{selectedCount===sources.length?'Clear selection':'Select all'}</button>}<button className="text-button" onClick={onAdd} disabled={busy}><Plus size={16}/> Add photos or videos</button></div></div><div className="source-cards">{packageSources.map(s=><article className="source-card package-source-card" key={s.id}><button className="source-thumb" onClick={()=>onReviewPackage(s)} disabled={busy} aria-label={"Review capture session "+s.name}><div className="thumb-fallback"><Camera size={25}/><span>{s.capturePackage!.frameCount.toLocaleString()} recorded frames</span></div><span className="preview-affordance"><Expand size={14}/>Review session</span></button><div className="source-caption"><button className="source-name" onClick={()=>onReviewPackage(s)} disabled={busy}>{s.capturePackage!.device}<small>{s.capturePackage!.state} · {formatBytes(s.byteLength)}</small></button><button className="icon-button remove-source" disabled={busy} aria-label={"Remove capture session "+s.name} onClick={()=>onRemove(s.id)}><Trash2 size={15}/></button></div></article>)}{sources.map(s=><article className={'source-card '+(selected.has(s.id)?'selected':'')} key={s.id}><button className="source-thumb" onClick={()=>setDetail(s)} aria-label={'Preview '+s.name}><Thumb source={s}/><span className="preview-affordance"><Expand size={14}/>Preview</span><span className="projection-chip">{s.projection==='unknown'?'SOURCE PREVIEW':s.projection==='equirectangular'?'360°':s.projection==='dual-fisheye'?'DUAL FISHEYE':'STANDARD'}</span></button><div className="source-caption"><Checkbox aria-label={'Select '+s.name} checked={selected.has(s.id)} onCheckedChange={v=>onSelect(s.id,v===true)} disabled={busy}/><button className="source-name" onClick={()=>setDetail(s)}>{s.name}<small>{formatBytes(s.byteLength)}</small></button><button className="icon-button remove-source" disabled={busy} title={"Remove "+s.name} aria-label={"Remove capture "+s.name} onClick={()=>onRemove(s.id)}><Trash2 size={15}/></button></div></article>)}<button className="add-source-card" onClick={onAdd} disabled={busy}><Plus size={22}/><span>{sources.length?'Another perspective':'Add your first capture'}</span><small>Stills · video · INSP · INSV</small></button></div></div><Dialog open={!!detail} onOpenChange={v=>{if(!v)setDetail(null)}}><DialogContent className="source-dialog"><DialogHeader><DialogTitle>{detail?.name}</DialogTitle><DialogDescription>Preview your photo or video. The original stays saved on this device.</DialogDescription></DialogHeader>{detail&&<><CapturePreview key={detail.blobKey} source={detail} busy={busy} onAttachPreview={file=>{if(busy)return;void openStore().then(async store=>{const saved=await store.putBlob(file);const next={...detail,previewBlobKey:saved.blobKey,previewName:file.name,previewMime:file.type};setDetail(next);onUpdate(next)}).catch(e=>toast.error(e.message))}}/><label className="field">Projection<Choice label="Capture projection" value={detail.projection} onChange={v=>{const next={...detail,projection:v as Source['projection']};setDetail(next);onUpdate(next)}} options={['unknown',['perspective','Regular / perspective'],['equirectangular','360° equirectangular'],['dual-fisheye','Dual fisheye · unstitched']]}/></label><label className="field">Lens profile<input value={detail.lensProfile??''} placeholder="e.g. insta360-air" onChange={e=>setDetail({...detail,lensProfile:e.target.value||null})} onBlur={()=>onUpdate(detail)}/></label><label className="field">Capture time (UTC) <span className="muted">leave blank if unknown</span><input type="datetime-local" value={detail.captureTime?detail.captureTime.slice(0,16):''} onChange={e=>{const next={...detail,captureTime:e.target.value?new Date(e.target.value+'Z').toISOString():null};setDetail(next);onUpdate(next)}}/></label><button className="secondary full danger-action" disabled={busy} onClick={()=>{onRemove(detail.id);setDetail(null)}}><Trash2 size={16}/>Remove capture from scene</button><p className="small muted">Previewing a capture does not submit it. Use the next-action button beside your captures to prepare 3D processing.</p></>}</DialogContent></Dialog></>
}
