import type {Layer,Revision} from './storage';

export function removeLayer(layers:Layer[],id:string){return layers.filter(l=>l.id!==id)}
export function restoreLayer(layers:Layer[],layer:Layer,index:number){
 if(layers.some(l=>l.id===layer.id))return layers;
 const next=[...layers];next.splice(Math.min(index,next.length),0,layer);return next;
}
export function sameAlignment(a:Layer,b:Layer){
 const dot=a.pose.quaternion.reduce((sum,v,i)=>sum+v*b.pose.quaternion[i],0)/(Math.hypot(...a.pose.quaternion)*Math.hypot(...b.pose.quaternion));
 return a.scale===b.scale&&a.pose.position.every((v,i)=>v===b.pose.position[i])&&Math.abs(Math.abs(dot)-1)<1e-10;
}
/** Follow ancestors, since timestamp ordering can include unrelated branches. */
export function alignmentHistory(revisions:Revision[],head:string,id:string){
 const byId=new Map(revisions.map(r=>[r.id,r])),seen=new Set<string>();
 let revision=byId.get(head),original:Layer|undefined,previous:Layer|undefined;
 const current=revision?.layers.find(l=>l.id===id);
 while(revision&&!seen.has(revision.id)){
  seen.add(revision.id);const layer=revision.layers.find(l=>l.id===id);
  if(layer){original=layer;if(current&&!previous&&!sameAlignment(current,layer))previous=layer;}
  revision=revision.parentRevisionId?byId.get(revision.parentRevisionId):undefined;
 }
 return {original,previous};
}
export function restoreAlignment(layer:Layer,saved:Layer):Layer{
 return {...layer,pose:structuredClone(saved.pose),scale:saved.scale,alignment:saved.alignment};
}
