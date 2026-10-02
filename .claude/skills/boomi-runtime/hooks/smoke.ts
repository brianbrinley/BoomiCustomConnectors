// The smoke test's process: No Data start → Message "Hello world" → Notify → Stop.
// Shapes follow Boomi Companion's process_component reference.

import { escapeXml } from './platform'

export const helloWorldProcess = (name: string, folderId?: string): string => {
  const folder = folderId ? ` folderId="${escapeXml(folderId)}"` : ''
  const greeting = escapeXml(`Hello world from ${name}`)
  return `<?xml version="1.0" encoding="UTF-8"?>
<bns:Component xmlns:bns="http://api.platform.boomi.com/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"${folder} name="${escapeXml(name)}" type="process">
  <bns:encryptedValues/>
  <bns:description>Created by the boomi-runtime mod's smoke test; deleted with the runtime.</bns:description>
  <bns:object>
    <process allowSimultaneous="false" enableUserLog="false" processLogOnErrorOnly="false" purgeDataImmediately="false" stopProcessingIfZeroDocuments="false" updateRunDates="true" workload="general">
      <shapes>
        <shape image="start" name="shape1" shapetype="start" userlabel="" x="48.0" y="46.0">
          <configuration>
            <noaction/>
          </configuration>
          <dragpoints>
            <dragpoint name="shape1.dragpoint1" toShape="shape2" x="224.0" y="56.0"/>
          </dragpoints>
        </shape>
        <shape image="message_icon" name="shape2" shapetype="message" userlabel="Hello world" x="240.0" y="48.0">
          <configuration>
            <message combined="false">
              <msgTxt>${greeting}</msgTxt>
              <msgParameters/>
            </message>
          </configuration>
          <dragpoints>
            <dragpoint name="shape2.dragpoint1" toShape="shape3" x="416.0" y="56.0"/>
          </dragpoints>
        </shape>
        <shape image="notify_icon" name="shape3" shapetype="notify" userlabel="" x="432.0" y="48.0">
          <configuration>
            <notify disableEvent="true" enableUserLog="false" perExecution="false" title="">
              <notifyMessage>${greeting}</notifyMessage>
              <notifyMessageLevel>INFO</notifyMessageLevel>
              <notifyParameters/>
            </notify>
          </configuration>
          <dragpoints>
            <dragpoint name="shape3.dragpoint1" toShape="shape4" x="608.0" y="56.0"/>
          </dragpoints>
        </shape>
        <shape image="stop_icon" name="shape4" shapetype="stop" x="624.0" y="48.0">
          <configuration>
            <stop continue="true"/>
          </configuration>
          <dragpoints/>
        </shape>
      </shapes>
    </process>
  </bns:object>
</bns:Component>
`
}
