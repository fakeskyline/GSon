"use strict";
function validateGeometry(g){
 const types=new Set(["Point","LineString","Polygon","MultiPoint","MultiLineString","MultiPolygon","GeometryCollection"]);
 if(!g||!types.has(g.type))throw Error("Неподдерживаемый тип геометрии: "+(g&&g.type));
 if(g.type==="GeometryCollection"){(g.geometries||[]).forEach(validateGeometry);return;}
 const walk=v=>{if(Array.isArray(v)&&v.length&&typeof v[0]==="number"){if(v.length<2||v.length>4||v.some(n=>!Number.isFinite(Number(n))))throw Error("Некорректная координата.");}else if(Array.isArray(v))v.forEach(walk);else throw Error("Некорректная структура координат.");};
 walk(g.coordinates);
}
function validateFeature(f){if(!f||f.type!=="Feature")throw Error("Объект не является GeoJSON Feature.");if(f.geometry)validateGeometry(f.geometry);}
function parseGeoJSON(text,name){
 let o;try{o=JSON.parse(text.replace(/^\uFEFF/,""));}catch(e){throw Error("Некорректный JSON: "+e.message);}
 let features=[];
 if(o.type==="FeatureCollection"){if(!Array.isArray(o.features))throw Error("В FeatureCollection отсутствует массив features.");features=o.features;}
 else if(o.type==="Feature")features=[o];
 else if(o.type&&o.coordinates!==undefined)features=[{type:"Feature",geometry:o,properties:{}}];
 else throw Error("Ожидался GeoJSON FeatureCollection, Feature или Geometry.");
 for(let i=0;i<features.length;i++){try{validateFeature(features[i]);}catch(e){throw Error("Ошибка в объекте №"+(i+1)+": "+e.message);}}
 return {layers:[{name:name.replace(/\.[^.]+$/,""),srs:4326,features}]};
}
self.onmessage=e=>{
 try{
  const {action,payload}=e.data||{};
  if(action==="parse")self.postMessage({result:parseGeoJSON(payload.text,payload.name)});
  else if(action==="stringify")self.postMessage({result:JSON.stringify(payload.data,null,2)});
  else throw Error("Неизвестная операция фонового обработчика.");
 }catch(error){self.postMessage({error:error&&error.message?error.message:String(error)});}
};
