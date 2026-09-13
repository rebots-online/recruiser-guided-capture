'use client';
import {useEffect,useRef,useState} from 'react';
import {Download,ExternalLink,Share2} from 'lucide-react';
import {Dialog,DialogContent,DialogHeader,DialogTitle,DialogDescription} from '@/components/ui/dialog';
import {openExportTab,type PreparedFile} from '@/lib/recruiser/export-handoff';

type SaveWindow=Window&{showSaveFilePicker?: (options:{suggestedName:string})=>Promise<{createWritable:()=>Promise<{write:(blob:Blob)=>Promise<void>;close:()=>Promise<void>}>}>};
export function PreparedFileActions({file,standalone=false}:{file:PreparedFile;standalone?:boolean}){
 const[url,setUrl]=useState(''),[status,setStatus]=useState(''),[saving,setSaving]=useState(false),[nativeSave,setNativeSave]=useState(false),[shareable,setShareable]=useState(false);
 const transfer=useRef<(()=>void)|null>(null);
 useEffect(()=>{
  const u=URL.createObjectURL(file.blob);setUrl(u);setStatus('');
  setNativeSave(window.self===window.top&&!!(window as SaveWindow).showSaveFilePicker);
  try{setShareable(!!navigator.canShare?.({files:[new File([file.blob],file.name,{type:file.blob.type})]}))}catch{setShareable(false)}
  return()=>{URL.revokeObjectURL(u);transfer.current?.();transfer.current=null};
 },[file]);
 async function save(){
  try{
   // Invoke before any await: the picker needs this button's user activation.
   const pending=(window as SaveWindow).showSaveFilePicker!({suggestedName:file.name});setSaving(true);
   const handle=await pending,writer=await handle.createWritable();await writer.write(file.blob);await writer.close();setStatus('File saved.');
  }catch(e){setStatus((e as Error).name==='AbortError'?'Save cancelled. Your file is still ready.':'Save was unavailable: '+(e as Error).message)}finally{setSaving(false)}
 }
 async function share(){try{await navigator.share({files:[new File([file.blob],file.name,{type:file.blob.type})]});setStatus('File handed to the selected application.')}catch(e){setStatus((e as Error).name==='AbortError'?'Sharing cancelled. Your file is still ready.':'Sharing was unavailable: '+(e as Error).message)}}
 return <div className="prepared-file"><p><strong>{file.name}</strong><br/><span className="muted">{(file.blob.size/1024**2).toFixed(1)} MB · ready to save</span></p>
 {nativeSave&&<button className="primary full" disabled={saving} onClick={()=>void save()}><Download size={16}/>{saving?'Saving…':'Save file…'}</button>}
 {url&&<a className="secondary full" href={url} download={file.name} onClick={()=>setStatus('Download requested. Check your browser downloads; if nothing appears, use another save option below.')}><Download size={16}/>Download here</a>}
 {!standalone&&<><button className="secondary full" onClick={()=>{transfer.current?.();transfer.current=openExportTab(file,setStatus)}}><ExternalLink size={16}/>Open save tab</button><p className="small muted">If the chat preview blocks downloads, open a save tab. The save tab can receive this prepared file directly, including work stored only in the preview. Keep this dialog open until the transfer finishes.</p></>}
 {shareable&&<button className="secondary full" onClick={()=>void share()}><Share2 size={16}/>Share file…</button>}
 {status&&<p className="notice" role="status">{status}</p>}
 </div>;
}
export default function PreparedFileDialog({file,onClose,queuedCount=1}:{file:PreparedFile|null;onClose:()=>void;queuedCount?:number}){
 return <Dialog open={!!file} onOpenChange={v=>{if(!v)onClose()}}><DialogContent><DialogHeader><DialogTitle>Your file is ready</DialogTitle><DialogDescription>Choose how to save it. Close this dialog after saving; the prepared file is kept in memory until then.</DialogDescription></DialogHeader>{file&&<PreparedFileActions file={file}/>}<button className="secondary full" onClick={onClose}>{queuedCount>1?"Next prepared file · "+(queuedCount-1)+" waiting":"Done"}</button></DialogContent></Dialog>;
}
