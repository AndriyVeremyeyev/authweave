import assert from "node:assert/strict";
import { test } from "node:test";
import { postProfileSection, profileReloadPath, profileFormIssues, profileWriteResponse, profileSaveSections,
  type ProfileSection } from "../src/lib/assessment/profile-save.ts";
import { profileFormFixture, profileSectionAction, profileSaveFixtureId as id } from "./fixtures/profile-save.mts";

for (const section of ["context","capabilities","auditability","operations"] as const) {
  test(`${section}: all checked outcomes bind the exact one-shot form payload and submitted version`, async () => {
    const action=profileSectionAction(section); const params=profileFormFixture(section); const before=params.toString();
    assert.equal(profileReloadPath(section,action),`/assessments/${id}?step=${section === "operations" ? "usage" : section}`);
    assert.deepEqual(profileFormIssues(section,params),[]);
    for (const [outcome,status] of [["saved",200],["conflict",409],["invalid",422],["locked",423]] as const) {
      let calls=0;
      const result=await postProfileSection(section,action,params,async (input,init)=>{
        calls++; assert.equal(input,action); assert.equal(init?.body,before);
        assert.equal(init?.method,"POST"); assert.equal(init?.credentials,"same-origin");
        assert.equal(init?.mode,"same-origin"); assert.equal(init?.redirect,"error"); assert.equal(init?.cache,"no-store");
        assert.deepEqual(init?.headers,{Accept:"application/json","Content-Type":"application/x-www-form-urlencoded"});
        assert.ok(init?.signal instanceof AbortSignal);
        const reply=profileWriteResponse(id,0,outcome);
        assert.equal(reply.status,status); assert.equal(reply.headers.get("cache-control"),"no-store");
        assert.equal(reply.headers.get("vary"),"Accept"); assert.equal(reply.headers.get("location"),null);
        return reply;
      });
      assert.equal(result,outcome); assert.equal(calls,1); assert.equal(params.toString(),before);
    }
  });
  test(`${section}: foreign, wrong-section and noncanonical destinations cannot send inputs or reload`,async()=>{
    let calls=0; const params=profileFormFixture(section);
    const own=profileSectionAction(section);
    const targets=[`https://other.example.test${own}`,`//other.example.test${own}`,`${own}?step=usage`,`${own}#scope`,`${own}/`,
      own.replace(id,id.toUpperCase()),...Object.keys(profileSaveSections).filter(key=>key!==section).map(key=>profileSectionAction(key as ProfileSection))];
    for(const action of targets) {
      assert.equal(profileReloadPath(section,action),null);
      assert.equal(await postProfileSection(section,action,params,async()=>{calls++; throw new Error("Unexpected IO");}),"invalid");
    }
    assert.equal(await postProfileSection("__proto__" as ProfileSection,own,params,async()=>{calls++;throw new Error("Unexpected IO");}),"invalid");
    assert.equal(calls,0);
  });
  test(`${section}: duplicate, foreign and section-invalid inputs remain unsent without normalization`,async()=>{
    for(const change of [(p:URLSearchParams)=>p.append("workspaceId","forged"),
      (p:URLSearchParams)=>p.append("expectedVersion","1"),(p:URLSearchParams)=>p.set("expectedVersion","01"),
      (p:URLSearchParams)=> section==="context" ? p.set("allowedCountries","US, US") :
        section==="capabilities" ? p.set("SCIM","AVAILABLE") : section === "operations" ? p.set("hosting", "AVAILABLE") : p.set("minimumRetentionDays","0")]) {
      const params=profileFormFixture(section); change(params); const before=params.toString(); let calls=0;
      assert.ok(profileFormIssues(section,params).length>0);
      assert.equal(await postProfileSection(section,profileSectionAction(section),params,async()=>{calls++;throw new Error("Unexpected IO");}),"invalid");
      assert.equal(calls,0); assert.equal(params.toString(),before);
    }
  });
  test(`${section}: contradictory, foreign, oversized or unreadable receipts cannot acknowledge success`,async()=>{
    const receipt={assessmentId:id,expectedVersion:0,outcome:"saved"};
    for(const response of [Response.json({...receipt,expectedVersion:1}),Response.json({...receipt,assessmentId:"foreign"}),
      Response.json({...receipt,profile:{private:"never displayed"}}),Response.json({...receipt,outcome:"conflict"}),
      Response.json(receipt,{status:409}),new Response("Private reply",{status:200,headers:{"Content-Type":"text/html"}}),
      new Response("x".repeat(1025),{headers:{"Content-Type":"application/json"}}),
      Response.redirect("https://other.example.test",303)]) {
      assert.equal(await postProfileSection(section,profileSectionAction(section),profileFormFixture(section),async()=>response),"uncertain");
    }
  });
  test(`${section}: input/access refusals are fixed outcomes; transport failure never retries or claims no commit`,async()=>{
    for(const [status,outcome] of [[400,"invalid"],[401,"signed-out"],[403,"forbidden"],[404,"not-found"],[503,"uncertain"]] as const) {
      assert.equal(await postProfileSection(section,profileSectionAction(section),profileFormFixture(section),async()=>new Response("Private upstream detail",{status})),outcome);
    }
    let calls=0;
    assert.equal(await postProfileSection(section,profileSectionAction(section),profileFormFixture(section),async()=>{calls++;throw new DOMException("Lost reply","TimeoutError");}),"uncertain");
    assert.equal(calls,1);
  });
}
