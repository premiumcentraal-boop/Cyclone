import test from "node:test";
import assert from "node:assert/strict";
import { livePhoneLabels } from "../.test-dist/core/livePhone.js";
test("Live Phone excludes virtual devices and does not invent readiness", () => {
 const labels = livePhoneLabels({connected:false,vision:false,control:false,enabled:true,stopped:false}, [{source:"VIRTUAL",name:"Virtual",connectionLabel:"Ready"},{source:"USB",name:"Pixel",connectionLabel:"Not paired"}]);
 assert.equal(labels.phone,"Pixel · Not paired"); assert.match(labels.control,/waiting/); assert.doesNotMatch(labels.vision,/Ready/);
});
test("Stop overrides old ready status", () => {
 const labels=livePhoneLabels({connected:true,vision:false,control:true,enabled:false,stopped:true},[]);
 assert.equal(labels.control,"Control · Stopped"); assert.match(labels.phone,/Connect and pair/);
});
