import {test} from 'node:test';
import assert from 'node:assert/strict';
import {PerspectiveCamera,Quaternion,Vector3,Group,Mesh,MeshStandardMaterial,BufferGeometry,Float32BufferAttribute,Color} from 'three';
import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
import {PLYLoader} from 'three/addons/loaders/PLYLoader.js';
import {flightWheel,nudgeLayer,nudgeKey} from '../../lib/recruiser/navigation';
import {exportCompositeGLB,exportCompositeGaussians,type GaussianObject} from '../../lib/recruiser/composite';
import {identityPose,type Layer} from '../../lib/recruiser/storage';
const close=(a:number,b:number)=>assert.ok(Math.abs(a-b)<1e-5,`${a} != ${b}`);
const base:Layer={id:'scan',name:'scan.ply',kind:'gaussian',blobKey:'test',sourceIds:[],pose:identityPose(),scale:1,visible:true,provenance:{},alignment:'unregistered'};

test('mouse travel follows camera orientation; lens zoom changes FOV without moving; modifiers expose other axes',()=>{
 const c=new PerspectiveCamera(55);c.quaternion.setFromAxisAngle(new Vector3(0,1,0),Math.PI/2);
 const e={deltaX:0,deltaY:-100,deltaMode:0,ctrlKey:false,metaKey:false,shiftKey:false,altKey:false};
 flightWheel(c,e,2);close(c.position.x,-.5);close(c.position.z,0);
 const position=c.position.clone();flightWheel(c,{...e,ctrlKey:true},2);assert.deepEqual(c.position.toArray(),position.toArray());assert.ok(c.fov<55);
 flightWheel(c,{...e,deltaY:0,deltaX:100,shiftKey:true},2);close(c.position.z,-.5);
 flightWheel(c,{...e,altKey:true},2);close(c.position.y,.5);
 const q=c.quaternion.clone();flightWheel(c,{...e,ctrlKey:true,shiftKey:true},2);assert.ok(q.angleTo(c.quaternion)>.1);
});

test('world-axis nudges retain original layer and origin; X flip corrects camera coordinates',()=>{
 const shifted=nudgeLayer(base,'move',0,3);assert.deepEqual(base.pose,identityPose());assert.deepEqual(shifted.pose.position,[3,0,0]);
 const flipped=nudgeLayer(shifted,'rotate',0,180);const q=new Quaternion(...flipped.pose.quaternion);
 close(new Vector3(0,1,0).applyQuaternion(q).y,-1);close(new Vector3(0,0,1).applyQuaternion(q).z,-1);assert.deepEqual(flipped.pose.position,[3,0,0]);
 close(new Quaternion(...nudgeLayer(flipped,'rotate',0,180).pose.quaternion).angleTo(new Quaternion()),0);
 assert.deepEqual(nudgeKey('PageUp','move'),{axis:1,sign:1});assert.deepEqual(nudgeKey('PageUp','rotate'),{axis:2,sign:1});assert.equal(nudgeKey('KeyW','move'),null);
});

// GLTFExporter uses browser FileReader to encode its final binary; no browser/GPU is needed for this fixture.
class Reader {result:ArrayBuffer|string|null=null;onloadend:(()=>void)|null=null;onerror:((e:unknown)=>void)|null=null;readAsArrayBuffer(b:Blob){void b.arrayBuffer().then(r=>{this.result=r;this.onloadend?.()}).catch(e=>this.onerror?.(e))}readAsDataURL(b:Blob){void b.arrayBuffer().then(r=>{this.result='data:'+b.type+';base64,'+Buffer.from(r).toString('base64');this.onloadend?.()})}}

test('combined GLB round-trip preserves two layer transforms and leaves live material unchanged',async()=>{
 Object.defineProperty(globalThis,'FileReader',{value:Reader,configurable:true});
 const g=new BufferGeometry().setAttribute('position',new Float32BufferAttribute([0,0,0,1,0,0,0,1,0],3));g.computeVertexNormals();
 const material=new MeshStandardMaterial({wireframe:true}),a=new Group(),b=new Group();a.name='first';b.name='second';a.add(new Mesh(g,material));b.add(new Mesh(g,material));a.position.set(4,5,6);b.position.set(-3,2,1);b.scale.setScalar(2);b.quaternion.setFromAxisAngle(new Vector3(1,0,0),Math.PI);
 const result=await new GLTFLoader().parseAsync(await(await exportCompositeGLB([a,b],'unknown')).arrayBuffer(),'');result.scene.updateMatrixWorld(true);
 assert.equal(result.scene.children.length,2);assert.deepEqual(result.scene.children[0].position.toArray(),[4,5,6]);close(result.scene.children[1].quaternion.angleTo(b.quaternion),0);assert.equal(result.scene.children[1].scale.x,2);assert.equal(material.wireframe,true);assert.equal(a.children[0].parent,a);
});

test('Gaussian merge bakes translation, rotation and scale into standard finite 3DGS PLY',async()=>{
 const make=()=>{const object=new Group() as GaussianObject;object.packedSplats={numSplats:1,forEachSplat:fn=>fn(0,new Vector3(1,0,0),new Vector3(1,2,3),new Quaternion(),.75,new Color(.8,.5,.2))};return object};
 const a=make(),b=make();b.position.set(3,4,5);b.scale.setScalar(2);b.quaternion.setFromAxisAngle(new Vector3(0,0,1),Math.PI/2);
 const blob=exportCompositeGaussians([a,b]),buffer=await blob.arrayBuffer(),header=new TextDecoder().decode(buffer.slice(0,1500)).split('end_header')[0];assert.match(header,/element vertex 2/);assert.doesNotMatch(header,/f_rest_/);
 const loader=new PLYLoader();loader.setCustomPropertyNameMapping({gaussianScale:['scale_0','scale_1','scale_2'],rotation:['rot_0','rot_1','rot_2','rot_3'],opacity:['opacity']});const geometry=loader.parse(buffer);
 const positions=Array.from(geometry.getAttribute('position').array);assert.deepEqual(positions.slice(0,3),[1,0,0]);close(positions[3],3);close(positions[4],6);close(positions[5],5);
 const scales=geometry.getAttribute('gaussianScale');close(Math.exp(scales.getX(1)),2);close(Math.exp(scales.getZ(1)),6);
 const rotation=geometry.getAttribute('rotation');close(rotation.getX(1),Math.SQRT1_2);close(rotation.getW(1),Math.SQRT1_2);close(geometry.getAttribute('opacity').getX(1),Math.log(3));
 assert.equal(a.position.x,0);assert.equal(b.scale.x,2);
});
