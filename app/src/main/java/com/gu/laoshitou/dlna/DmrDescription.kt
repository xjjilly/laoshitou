// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

package com.gu.laoshitou.dlna

internal object DmrDescription {

    fun build(udn: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"
              xmlns:dlna="urn:schemas-dlna-org:device-1-0">
          <specVersion>
            <major>1</major>
            <minor>0</minor>
          </specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>老石头 DMR</friendlyName>
            <manufacturer>LaoShiTou</manufacturer>
            <modelDescription>LaoShiTou DLNA renderer</modelDescription>
            <modelName>LaoShiTou DMR</modelName>
            <modelNumber>0.1</modelNumber>
            <UDN>$udn</UDN>
            <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <SCPDURL>/dlna/avtransport/scpd.xml</SCPDURL>
                <controlURL>/dlna/avtransport/control</controlURL>
                <eventSubURL>/dlna/avtransport/event</eventSubURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <SCPDURL>/dlna/renderingcontrol/scpd.xml</SCPDURL>
                <controlURL>/dlna/renderingcontrol/control</controlURL>
                <eventSubURL>/dlna/renderingcontrol/event</eventSubURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                <SCPDURL>/dlna/connectionmanager/scpd.xml</SCPDURL>
                <controlURL>/dlna/connectionmanager/control</controlURL>
                <eventSubURL>/dlna/connectionmanager/event</eventSubURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()
}
