export type Vec3=[number,number,number];
export type Quat=[number,number,number,number];
export type Pose={position:Vec3;quaternion:Quat};
export type Source={id:string;sceneId:string;sha256:string;name:string;byteLength:number;mime:string;importedAt:string;captureTime:string|null;projection:'perspective'|'equirectangular'|'dual-fisheye'|'unknown';lensProfile:string|null;blobKey:string;vignetteId:string;previewBlobKey?:string;previewName?:string;previewMime?:string};
export type Layer={id:string;sourceIds:string[];name:string;kind:'mesh'|'points'|'gaussian';blobKey:string;pose:Pose;scale:number;visible:boolean;provenance:Record<string,unknown>;alignment:'unregistered'|'manual'|'registered'};
export type Revision={id:string;sceneId:string;parentRevisionId:string|null;worldFrameId:string;unitStatus:'metres'|'unknown';createdAt:string;layers:Layer[];vignetteIds:string[];removedSourceIds?:string[];title:string};
export type SavedJob={id:string;sceneId:string;[key:string]:unknown};
export type SceneData={id:string;head:string;revision:Revision;revisions:Revision[];sources:Source[];jobs:SavedJob[];persistent:boolean};
export const identityPose=():Pose=>({position:[0,0,0],quaternion:[0,0,0,1]});
export function validateLayer(l:Layer){
 if(!l||typeof l.id!=='string'||typeof l.name!=='string'||!['mesh','points','gaussian'].includes(l.kind)||typeof l.blobKey!=='string'||!Array.isArray(l.sourceIds)||!l.sourceIds.every(x=>typeof x==='string')||typeof l.visible!=='boolean')throw Error('Invalid layer record');
 if(!l.pose||l.pose.position.length!==3||l.pose.quaternion.length!==4||![...l.pose.position,...l.pose.quaternion,l.scale].every(Number.isFinite)||l.scale<=0)throw Error('Invalid layer transform');
 if(Math.abs(Math.hypot(...l.pose.quaternion)-1)>.001)throw Error('Layer quaternion must be normalized');
}
export function validateRevision(r:Revision){if(!r||typeof r.id!=='string'||typeof r.sceneId!=='string'||typeof r.worldFrameId!=='string'||!['metres','unknown'].includes(r.unitStatus)||!Array.isArray(r.layers)||!Array.isArray(r.vignetteIds))throw Error('Invalid scene revision');if(r.removedSourceIds!==undefined&&(!Array.isArray(r.removedSourceIds)||!r.removedSourceIds.every(id=>typeof id==='string')))throw Error('Invalid removed capture references');r.layers.forEach(validateLayer);if(new Set(r.layers.map(l=>l.id)).size!==r.layers.length)throw Error('Duplicate layer identity');}
export async function hashBlob(blob:Blob){return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer()))).map(x=>x.toString(16).padStart(2,'0')).join('')}
const req=<T>(r:IDBRequest<T>)=>new Promise<T>((resolve,reject)=>{r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)});
const done=(tx:IDBTransaction)=>new Promise<void>((resolve,reject)=>{tx.oncomplete=()=>resolve();tx.onabort=()=>reject(tx.error??Error('Transaction aborted'));tx.onerror=()=>reject(tx.error)});
let connection:Promise<IDBDatabase>|null=null;
function database(){if(!connection)connection=new Promise((resolve,reject)=>{const r=indexedDB.open('recruiser-v1',1);r.onupgradeneeded=()=>{for(const name of ['blobs','sources','revisions','scenes','jobs'])r.result.createObjectStore(name,{keyPath:'id'})};r.onsuccess=()=>resolve(r.result);r.onerror=()=>{connection=null;reject(r.error)}});return connection}
export async function openStore(){
 const db=await database();
 async function all<T>(name:string){return req<T[]>(db.transaction(name).objectStore(name).getAll())}
 async function write(name:string,value:unknown){const tx=db.transaction(name,'readwrite');const p=done(tx);tx.objectStore(name).put(value);await p}
 return {
  async putBlob(blob:Blob){const sha256=await hashBlob(blob);await write('blobs',{id:sha256,blob});return{blobKey:sha256,sha256,byteLength:blob.size}},
  async getBlob(blobKey:string){const r=await req<{blob:Blob}|undefined>(db.transaction('blobs').objectStore('blobs').get(blobKey));if(!r)throw Error('Missing local asset '+blobKey.slice(0,8));return r.blob},
  async appendRevision(sceneId:string,expectedHead:string|null,revision:Revision){
   validateRevision(revision);if(revision.sceneId!==sceneId||revision.parentRevisionId!==expectedHead)throw Error('Revision identity mismatch');
   return new Promise<string>((resolve,reject)=>{const tx=db.transaction(['scenes','revisions'],'readwrite');const scenes=tx.objectStore('scenes'),rs=tx.objectStore('revisions');let conflict=false,duplicate=false;
    const check=rs.get(revision.id);check.onsuccess=()=>{if(check.result){if(JSON.stringify(check.result)!==JSON.stringify(revision)){conflict=true;tx.abort();return}duplicate=true;return}
    const head=scenes.get(sceneId);head.onsuccess=()=>{if((head.result?.head??null)!==expectedHead){conflict=true;tx.abort();return}rs.add(revision);scenes.put({id:sceneId,head:revision.id,title:revision.title,updatedAt:revision.createdAt})}};
    tx.oncomplete=()=>resolve(revision.id);tx.onabort=()=>reject(Error(conflict?'REVISION_CONFLICT':tx.error?.message??'Unable to save revision'));tx.onerror=()=>{};
   });
  },
  async getScene(sceneId:string):Promise<SceneData>{const scene=await req<{id:string;head:string}>(db.transaction('scenes').objectStore('scenes').get(sceneId));if(!scene)throw Error('Scene not found');const revisions=(await all<Revision>('revisions')).filter(r=>r.sceneId===sceneId).sort((a,b)=>a.createdAt.localeCompare(b.createdAt));const revision=revisions.find(r=>r.id===scene.head);if(!revision)throw Error('Scene revision missing');return {...scene,revision,revisions,sources:(await all<Source>('sources')).filter(x=>x.sceneId===sceneId),jobs:(await all<SavedJob>('jobs')).filter(x=>x.sceneId===sceneId),persistent:typeof navigator!=='undefined'&&!!(await navigator.storage?.persisted?.())}},
  async listScenes(){return (await all<{id:string;head:string;title:string;updatedAt:string}>('scenes')).sort((a,b)=>b.updatedAt.localeCompare(a.updatedAt))},
  async saveSource(source:Source){if(!source.id||!source.sceneId||!source.sha256||!source.blobKey||!Number.isFinite(source.byteLength))throw Error('Invalid source');await write('sources',source)},
  async saveJob(job:SavedJob){await write('jobs',job)},
  async getJobs(sceneId:string){return(await all<SavedJob>('jobs')).filter(x=>x.sceneId===sceneId)},
  async importAtomic(payload:{revisions:Revision[];sources:Source[];jobs:SavedJob[];scene:{id:string;head:string;title:string;updatedAt:string};blobs:{id:string;blob:Blob}[]}){payload.revisions.forEach(validateRevision);const tx=db.transaction(['blobs','sources','revisions','scenes','jobs'],'readwrite');const p=done(tx);for(const b of payload.blobs)tx.objectStore('blobs').put(b);for(const x of payload.sources)tx.objectStore('sources').add(x);for(const x of payload.revisions)tx.objectStore('revisions').add(x);for(const x of payload.jobs)tx.objectStore('jobs').add(x);tx.objectStore('scenes').add(payload.scene);await p},
 };
}
