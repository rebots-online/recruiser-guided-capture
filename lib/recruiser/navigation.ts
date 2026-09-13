import {MathUtils, Quaternion, Vector3, type PerspectiveCamera} from 'three';
import type {Layer, Quat, Vec3} from './storage';

export type WheelGesture = {deltaX:number;deltaY:number;deltaMode:number;ctrlKey:boolean;metaKey:boolean;shiftKey:boolean;altKey:boolean};
export function flightWheel(camera:PerspectiveCamera,e:WheelGesture,speed:number){
 const raw=Math.abs(e.deltaY)>=Math.abs(e.deltaX)?e.deltaY:e.deltaX;
 const delta=MathUtils.clamp(raw*(e.deltaMode===1?16:e.deltaMode===2?800:1),-400,400);
 const zoom=e.ctrlKey||e.metaKey;
 if(zoom&&e.shiftKey)camera.rotateZ(-delta*.002);
 else if(zoom){camera.fov=MathUtils.clamp(camera.fov*Math.exp(delta*.002),10,110);camera.updateProjectionMatrix()}
 else if(e.altKey)camera.translateY(-delta*speed*.0025);
 else if(e.shiftKey)camera.translateX(delta*speed*.0025);
 else camera.translateZ(delta*speed*.0025);
}

export type NudgeMode='move'|'rotate';
export function nudgeLayer(layer:Layer,mode:NudgeMode,axis:0|1|2,amount:number):Layer{
 const position=[...layer.pose.position] as Vec3;
 const q=new Quaternion(...layer.pose.quaternion);
 if(mode==='move')position[axis]+=amount;
 else {const direction=new Vector3().setComponent(axis,1);q.premultiply(new Quaternion().setFromAxisAngle(direction,MathUtils.degToRad(amount))).normalize()}
 return {...layer,pose:{position,quaternion:q.toArray() as Quat},alignment:'manual'};
}
export function nudgeKey(code:string,mode:NudgeMode):{axis:0|1|2;sign:number}|null{
 const move:Record<string,{axis:0|1|2;sign:number}>={ArrowLeft:{axis:0,sign:-1},ArrowRight:{axis:0,sign:1},ArrowUp:{axis:2,sign:-1},ArrowDown:{axis:2,sign:1},PageUp:{axis:1,sign:1},PageDown:{axis:1,sign:-1}};
 const rotate:Record<string,{axis:0|1|2;sign:number}>={ArrowLeft:{axis:1,sign:1},ArrowRight:{axis:1,sign:-1},ArrowUp:{axis:0,sign:1},ArrowDown:{axis:0,sign:-1},PageUp:{axis:2,sign:1},PageDown:{axis:2,sign:-1}};
 return (mode==='move'?move:rotate)[code]??null;
}
