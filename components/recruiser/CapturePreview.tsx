'use client';
import {useEffect,useRef,useState} from 'react';
import {openStore,type Source} from '@/lib/recruiser/storage';
import {prepareMediaPreview,inspectMediaProjection,type Projection} from '@/lib/recruiser/ingest';

export default function CapturePreview({source,onAttachPreview,busy}:{source:Source;onAttachPreview:(f:File)=>void;busy:boolean}){
 const[url,setUrl]=useState(''),[message,setMessage]=useState('Opening capture…'),[dimensions,setDimensions]=useState('');
 const[kind,setKind]=useState(''),[projection,setProjection]=useState<Projection>(source.projection);
 const timeout=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);
 const previewName=source.previewName||source.name;
 const video=kind==='video';
 useEffect(()=>{let cancelled=false,u='';setUrl('');setMessage('Opening capture…');setDimensions('');
  void openStore().then(s=>s.getBlob(source.previewBlobKey||source.blobKey)).then(async blob=>{
   const preview=await prepareMediaPreview(blob,previewName);
   const detected:Projection=source.previewBlobKey?'unknown':source.projection==='unknown'?await inspectMediaProjection(blob,source.name):source.projection;
   if(cancelled)return;setKind(preview.kind);setProjection(detected);
   if(preview.kind==='unsupported'){setMessage('No browser preview is available for this capture. Attach a compatible preview copy below. The original is retained.');return}
   u=URL.createObjectURL(preview.blob);setUrl(u);setMessage('Loading preview…');
   timeout.current=setTimeout(()=>{if(!cancelled)setMessage('The preview has not loaded. Try a compatible preview copy below. The original is retained.')},12000);
  }).catch(()=>{if(!cancelled)setMessage('The saved original could not be opened.')});
  return()=>{cancelled=true;clearTimeout(timeout.current);if(u)URL.revokeObjectURL(u)};
 },[source.blobKey,source.mime,source.name,source.projection,source.previewBlobKey,source.previewMime,previewName]);
 function inspect(el:HTMLVideoElement){clearTimeout(timeout.current);if(el.videoWidth>0&&el.videoHeight>0){setDimensions(`${el.videoWidth} × ${el.videoHeight}`);setMessage('')}else setMessage('No video picture is available from this browser. Audio may still play. The file may use an unsupported video codec or contain only audio.')}
 return <div className="capture-preview"><div className="media-preview">{url&&(video?<video key={url} src={url} controls playsInline preload="auto" onLoadedMetadata={e=>inspect(e.currentTarget)} onLoadedData={e=>inspect(e.currentTarget)} onPlaying={e=>inspect(e.currentTarget)} onResize={e=>inspect(e.currentTarget)} onError={()=>{clearTimeout(timeout.current);setMessage('This browser could not decode the video. The original is retained.')}} />:<img src={url} alt={source.name} onLoad={e=>{clearTimeout(timeout.current);setDimensions(`${e.currentTarget.naturalWidth} × ${e.currentTarget.naturalHeight}`);setMessage('')}} onError={()=>{clearTimeout(timeout.current);setMessage('This browser could not decode this image. The original is retained.')}}/>)}</div>
 {message&&<p className="notice" role="status">{message}{!message.includes('…')&&video&&' Attach an H.264 MP4 or VP9 WebM preview copy below. The original stays attached for reconstruction.'}</p>}
 <div className="preview-caption"><span>{dimensions}{projection==='dual-fisheye'?' · Dual-fisheye photo':projection==='equirectangular'?' · Flat panorama preview':kind==='image'?' · Photo preview':''}</span>{url&&<a href={url} download={previewName}>{source.previewBlobKey?'Download preview':'Download original'}</a>}</div>{projection==='dual-fisheye'&&<p className="small muted">These are the camera’s two lens views. Stitching and 3D reconstruction are separate processing steps.{source.projection==='unknown'&&' For 3D processing, choose “Dual fisheye · unstitched” in Projection below.'}</p>}<label className="preview-attach">Use a compatible preview copy<input type="file" disabled={busy} accept=".mp4,.webm,.jpg,.jpeg,.png" onChange={e=>{const file=e.target.files?.[0];if(file)onAttachPreview(file);e.target.value=''}}/></label>{source.previewBlobKey&&<p className="small muted">Showing {previewName}. Your original capture is retained and used for reconstruction.</p>}</div>;
}
