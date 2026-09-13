export type PreparedFile={blob:Blob;name:string};
export const EXPORT_CHANNEL='recruiser-export-v1';
export function validHandoff(event:MessageEvent,peer:Window|null,origin:string,session:string){
 return !!peer&&event.source===peer&&event.origin===origin&&event.data?.channel===EXPORT_CHANNEL&&event.data?.session===session;
}
/** An explicit user gesture opens a save-only page; original data stays in this browser. */
export function openExportTab(file:PreparedFile,onStatus:(message:string)=>void){
 const session=crypto.randomUUID(),origin=location.origin;
 let popup:Window|null;
 try{popup=window.open('/save-export#'+session,'recruiser-export-'+session)}catch{onStatus('This browser context could not open a save tab. Your file is still ready here.');return ()=>{}}
 if(!popup){onStatus('The save tab was blocked. Allow this site to open a tab, then try again.');return ()=>{}}
 let sent=false;
 const receive=(event:MessageEvent)=>{
  if(!validHandoff(event,popup,origin,session))return;
  if(event.data.type==='ready'&&!sent){
   try{popup.postMessage({channel:EXPORT_CHANNEL,session,type:'file',...file},origin);sent=true;onStatus('Transferring the prepared file to the save tab…')}
   catch{onStatus('The browser could not transfer the file. It is still ready here.');cleanup()}
  }
  if(event.data.type==='received'){onStatus('File ready in the save tab. Click Save file there.');cleanup()}
 };
 const cleanup=()=>{window.removeEventListener('message',receive);clearTimeout(timeout)};
 const timeout=setTimeout(()=>{cleanup();onStatus('The save tab could not receive the file. Keep this dialog open and try Download here or Share file. Browser embedding rules may block the transfer.')},45000);
 window.addEventListener('message',receive);
 onStatus('Keep this dialog open while the save tab connects…');
 return cleanup;
}
