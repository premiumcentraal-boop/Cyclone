import test from"node:test";import assert from"node:assert/strict";import{MockDesktopService,createMockDevices}from"../.test-dist/services/mockDesktopService.js";
for(const count of[1,2,4,8,12])test(`mock backend exposes ${count} device fleet`,async()=>{const service=new MockDesktopService(count);assert.equal((await service.listDevices()).length,count)});
test("mock fleet exposes sleeping state",()=>{assert.ok(createMockDevices(4).some(d=>d.state==="SLEEPING"))});
test("mock fleet can report clipboard unavailable on a paired device",()=>{const devices=createMockDevices(8);assert.ok(devices.some(d=>d.paired&&!d.capabilities.clipboard))});
