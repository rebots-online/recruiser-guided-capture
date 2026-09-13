import {Group,Scene,Vector3,Quaternion,Color,type Object3D} from 'three';
import {clone} from 'three/addons/utils/SkeletonUtils.js';
import {GLTFExporter} from 'three/addons/exporters/GLTFExporter.js';
import * as WebGLTextureUtils from 'three/addons/utils/WebGLTextureUtils.js';

export async function exportCompositeGLB(objects:Object3D[],unitStatus:string){
 if(!objects.length)throw Error('Show at least one mesh or point layer to export.');
 const scene=new Scene();scene.name='Recruiser composite';scene.userData={unitStatus,upAxis:'Y'};
 const materials:any[]=[];
 try{
  for(const object of objects){const copy=clone(object);copy.traverse((o:any)=>{if(o.material){const source=Array.isArray(o.material)?o.material:[o.material];const copied=source.map((m:any)=>{const c=m.clone();c.wireframe=false;materials.push(c);return c});o.material=Array.isArray(o.material)?copied:copied[0]}});scene.add(copy)}
  scene.updateMatrixWorld(true);
  const result=await new GLTFExporter().setTextureUtils(WebGLTextureUtils).parseAsync(scene,{binary:true,onlyVisible:true});
  if(!(result instanceof ArrayBuffer))throw Error('GLB export did not produce a binary file.');
  return new Blob([result],{type:'model/gltf-binary'});
 }finally{for(const m of materials)m.dispose()}
}

type SplatData={numSplats:number;forEachSplat:(callback:(i:number,center:Vector3,scales:Vector3,quaternion:Quaternion,opacity:number,color:Color)=>void)=>void};
export type GaussianObject=Object3D&{packedSplats?:SplatData;extSplats?:SplatData};
function splatSource(o:GaussianObject){const source=o.packedSplats??o.extSplats;if(!source)throw Error('Original splat data is unavailable for export.');return source}
export function exportCompositeGaussians(objects:GaussianObject[]){
 const count=objects.reduce((n,o)=>n+splatSource(o).numSplats,0);
 if(!count)throw Error('Show at least one Gaussian splat layer to export.');
 // Standard SH degree zero 3DGS PLY. View-dependent harmonics are intentionally omitted.
 const fields=['x','y','z','f_dc_0','f_dc_1','f_dc_2','opacity','scale_0','scale_1','scale_2','rot_0','rot_1','rot_2','rot_3'];
 const byteLength=count*fields.length*4;if(byteLength>1024**3)throw Error('Gaussian export exceeds the 1 GiB limit. Export fewer visible layers.');
 const bytes=new ArrayBuffer(byteLength),view=new DataView(bytes);let offset=0;
 for(const object of objects){object.updateWorldMatrix(true,false);const world=new Group();object.matrixWorld.decompose(world.position,world.quaternion,world.scale);
  splatSource(object).forEachSplat((_i,center,scales,q,opacity,color)=>{
   const p=center.clone().applyMatrix4(object.matrixWorld),s=scales.clone().multiply(world.scale),rotation=q.clone().premultiply(world.quaternion).normalize();
   const a=Math.min(1-1e-7,Math.max(1e-7,opacity));
   const values=[...p.toArray(),(color.r-.5)/.28209479177387814,(color.g-.5)/.28209479177387814,(color.b-.5)/.28209479177387814,Math.log(a/(1-a)),...s.toArray().map((v:number)=>Math.log(Math.max(v,1e-12))),rotation.w,rotation.x,rotation.y,rotation.z];
   if(!values.every(Number.isFinite))throw Error('A splat contains an invalid transform or color.');
   for(const v of values){view.setFloat32(offset,v,true);offset+=4}
  });
 }
 if(offset!==byteLength)throw Error('Gaussian count changed during export. Try again after loading finishes.');
 const header=`ply\nformat binary_little_endian 1.0\ncomment Recruiser combined base-color export; SH degree 0; Y up\nelement vertex ${count}\n${fields.map(p=>'property float '+p).join('\n')}\nend_header\n`;
 return new Blob([header,bytes],{type:'application/octet-stream'});
}
