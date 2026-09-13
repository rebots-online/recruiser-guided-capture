'use client';
import {useEffect,useState} from 'react';
import {PreparedFileActions} from '@/components/recruiser/PreparedFileDialog';
import {EXPORT_CHANNEL,validHandoff,type PreparedFile} from '@/lib/recruiser/export-handoff';

export default function SaveExport(){
 const[file,setFile]=useState<PreparedFile|null>(null),[message,setMessage]=useState('Connecting to your workspace…');
 useEffect(()=>{
  const session=location.hash.slice(1),peer=window.opener as Window|null,origin=location.origin;
  if(!peer||!session){setMessage('Open this save tab from the “Your file is ready” dialog in Recruiser. No project is loaded on this page.');return}
  let received=false;
  const receive=(event:MessageEvent)=>{
   if(received||!validHandoff(event,peer,origin,session)||event.data.type!=='file'||!(event.data.blob instanceof Blob)||typeof event.data.name!=='string')return;
   received=true;setFile({blob:event.data.blob,name:event.data.name});setMessage('Save your file below. It has not been uploaded to a server.');
   peer.postMessage({channel:EXPORT_CHANNEL,session,type:'received'},origin);window.opener=null;clearInterval(timer);clearTimeout(timeout);
  };
  const request=()=>peer.postMessage({channel:EXPORT_CHANNEL,session,type:'ready'},origin);
  window.addEventListener('message',receive);const timer=setInterval(request,750);
  const timeout=setTimeout(()=>{clearInterval(timer);if(!received)setMessage('The workspace did not connect. Keep its export dialog open and try Open save tab again. Browser popup or embedding rules may prevent this transfer.')},45000);
  request();return()=>{clearInterval(timer);clearTimeout(timeout);window.removeEventListener('message',receive)};
 },[]);
 return <main className="save-export-page"><div className="save-export-card"><h1>Recruiser · Save export</h1><p className="muted" role="status">{message}</p>{file&&<PreparedFileActions file={file} standalone/>}<p className="small muted">Keep this tab open until your file is saved. This page does not start the 3D viewer.</p></div></main>;
}
