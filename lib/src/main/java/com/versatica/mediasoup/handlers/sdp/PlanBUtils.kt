package com.versatica.mediasoup.handlers.sdp

import com.dingsoft.sdptransform.MediaAttributes
import com.dingsoft.sdptransform.SessionDescription
import org.webrtc.MediaStreamTrack

/**
 * @author wolfhan
 */

object PlanBUtils {

    /**
     * Fill the given RTP parameters for the given track.
     *
     * @param {RTCRtpParameters} rtpParameters -  RTP parameters to be filled.
     * @param {SessionDescription} sdpObj - Local SDP Object generated by sdp-transform.
     * @param {MediaStreamTrack} track
     */
    fun fillRtpParametersForTrack(
        rtpParameters: RTCRtpParameters,
        sdpObj: SessionDescription,
        track: MediaStreamTrack
    ) {
        val kind = track.kind()
        val rtcp = RTCRtcpParameters(
            cname = null,
            reducedSize = true,
            mux = true
        )

        val section = sdpObj.media.find {
            it.type == kind
        } ?: throw Exception("m=$kind section not found")

        // First media SSRC (or the only one).
        var firstSsrc: Long? = null

        // Get all the SSRCs.
        val ssrcs = mutableSetOf<Long>()
        val ssrcList = section.ssrcs
        if (ssrcList != null) {
            for (line in ssrcList) {
                if (line.attribute != "msid")
                    continue

                val trackId = line.value?.split(" ")?.get(1)
                if (trackId == track.id()) {
                    val ssrc = line.id
                    ssrcs.add(ssrc)

                    if (firstSsrc == null)
                        firstSsrc = ssrc
                }
            }
        }

        if (ssrcs.size == 0)
            throw Exception("a=ssrc line not found for local track [track.id:${track.id()}]")

        // Get media and RTX SSRCs.
        val ssrcToRtxSsrc = mutableMapOf<Long, Long>()
        // First assume RTX is used.
        val ssrcGroups = section.ssrcGroups
        if (ssrcGroups != null) {
            for (line in ssrcGroups) {
                if (line.semantics != "FID")
                    continue

                val splitList = line.ssrcs.split(Regex("\\s+"))
                if (splitList.size == 2) {
                    val ssrc = splitList[0].toLongOrNull()
                    val rtxSsrc = splitList[1].toLongOrNull()
                    if (ssrc != null && rtxSsrc != null && ssrcs.contains(ssrc)) {
                        // Remove both the SSRC and RTX SSRC from the Set so later we know that they
                        // are already handled.
                        ssrcs.remove(ssrc)
                        ssrcs.remove(rtxSsrc)

                        // Add to the map.
                        ssrcToRtxSsrc[ssrc] = rtxSsrc
                    }
                }
            }
        }

        // If the Set of SSRCs is not empty it means that RTX is not being used, so take
        // media SSRCs from there.
        for (ssrc in ssrcs) {
            // Add to the map.
            ssrcToRtxSsrc[ssrc] = 0
        }

        // Get RTCP info.
        val ssrcCnameLine = section.ssrcs?.find {
            it.attribute == "cname" && it.id == firstSsrc
        }
        if (ssrcCnameLine != null)
            rtcp.cname = ssrcCnameLine.value

        // Fill RTP parameters.
        rtpParameters.rtcp = rtcp
        rtpParameters.encodings = arrayListOf()

        val simulcast = ssrcToRtxSsrc.size > 1
        val simulcastProfiles = arrayListOf("low", "medium", "high")

        for ((ssrc, rtxSsrc) in ssrcToRtxSsrc) {
            val encoding = RtpEncoding(ssrc = ssrc)

            if (rtxSsrc > 0) {
                val rtx = hashMapOf<String, Long>()
                rtx["ssrc"] = rtxSsrc
                encoding.rtx = rtx
            }

            if (simulcast)
                encoding.profile = simulcastProfiles.removeAt(0)

            rtpParameters.encodings?.add(encoding)
        }
    }

    /**
     * Adds simulcast into the given SDP for the given track.
     *
     * @param {SessionDescription} sdpObj - Local SDP Object generated by sdp-transform.
     * @param {MediaStreamTrack} track
     */
    fun addSimulcastForTrack(sdpObj: SessionDescription, track: MediaStreamTrack) {
        val kind = track.kind()
        val section = sdpObj.media.find {
            it.type == kind
        } ?: throw Exception("m=$kind section not found")

        var ssrc: Long = 0
        var rtxSsrc: Long? = null
        var msid = ""

        // Get the SSRC.
        val ssrcMsidLine = section.ssrcs?.find {
            if (it.attribute != "msid")
                return@find false

            val trackId = it.value?.split(" ")?.get(1)

            if (trackId == track.id()) {
                ssrc = it.id
                msid = it.value?.split(" ")?.get(0).toString()

                return@find true
            }
            return@find false

        } ?: throw Exception("a=ssrc line not found for local track [track.id:${track.id()}]")

        // Get the SSRC for RTX.
        section.ssrcGroups?.any {
            if (it.semantics != "FID")
                return@any false

            val ssrcs = it.ssrcs.split(Regex("\\s+"))
            if (ssrcs.size == 2) {
                try {
                    if (ssrcs[0].toLong() == ssrc) {
                        rtxSsrc = ssrcs[1].toLong()
                        return@any true
                    }
                } catch (e: NumberFormatException) {

                }
            }
            return@any false
        }

        val ssrcCnameLine = section.ssrcs?.find {
            it.attribute == "cname" && it.id == ssrc
        } ?: throw Exception("CNAME line not found for local track [track.id:${track.id()}]")

        val cname = ssrcCnameLine.value
        val ssrc2 = ssrc.plus(1)
        val ssrc3 = ssrc.plus(2)

        if (section.ssrcGroups == null) {
            section.ssrcGroups = mutableListOf()
        }
        section.ssrcGroups?.add(
            MediaAttributes.SsrcGroup(
                semantics = "SIM",
                ssrcs = "$ssrc $ssrc2 $ssrc3"
            )
        )

        section.ssrcs?.add(
            MediaAttributes.Ssrc(
                id = ssrc2,
                attribute = "cname",
                value = cname
            )
        )

        section.ssrcs?.add(
            MediaAttributes.Ssrc(
                id = ssrc2,
                attribute = "msid",
                value = "$msid ${track.id()}"
            )
        )

        section.ssrcs?.add(
            MediaAttributes.Ssrc(
                id = ssrc3,
                attribute = "cname",
                value = cname
            )
        )

        section.ssrcs?.add(
            MediaAttributes.Ssrc(
                id = ssrc3,
                attribute = "msid",
                value = "$msid ${track.id()}"
            )
        )

        if (rtxSsrc != null) {
            val rtxSsrc2 = rtxSsrc?.plus(1)
            val rtxSsrc3 = rtxSsrc?.plus(2)

            section.ssrcGroups?.add(
                MediaAttributes.SsrcGroup(
                    semantics = "FID",
                    ssrcs = "$ssrc2 $rtxSsrc2"
                )
            )

            section.ssrcs?.add(
                MediaAttributes.Ssrc(
                    id = rtxSsrc2 ?: 0,
                    attribute = "cname",
                    value = cname
                )
            )

            section.ssrcs?.add(
                MediaAttributes.Ssrc(
                    id = rtxSsrc2 ?: 0,
                    attribute = "msid",
                    value = "$msid ${track.id()}"
                )
            )

            section.ssrcGroups?.add(
                MediaAttributes.SsrcGroup(
                    semantics = "FID",
                    ssrcs = "$ssrc3 $rtxSsrc3"
                )
            )

            section.ssrcs?.add(
                MediaAttributes.Ssrc(
                    id = rtxSsrc3 ?: 0,
                    attribute = "cname",
                    value = cname
                )
            )

            section.ssrcs?.add(
                MediaAttributes.Ssrc(
                    id = rtxSsrc3 ?: 0,
                    attribute = "msid",
                    value = "$msid ${track.id()}"
                )
            )
        }
    }

}
