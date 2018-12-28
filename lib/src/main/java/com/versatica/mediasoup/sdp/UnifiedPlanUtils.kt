package com.versatica.mediasoup.sdp

import com.dingsoft.sdptransform.MediaAttributes
import com.dingsoft.sdptransform.SessionDescription
import org.webrtc.MediaStreamTrack

/**
 * @author wolfhan
 */

fun fillRtpParametersForTrack(
    rtpParameters: RTCRtpParameters, sdpObj: SessionDescription, track: MediaStreamTrack,
    mid: String? = null, planBSimulcast: Boolean = false
) {
    val section = findMediaSection(sdpObj, track, mid)

    if (mid !== null)
        rtpParameters.muxId = mid

    val rtcpParameters = RTCRtcpParameters(
        cname = null,
        reducedSize = true
    )
    rtcpParameters.mux = true
    rtpParameters.rtcp = rtcpParameters

    // Get the SSRC and CNAME.
    val ssrcCnameLine = section?.ssrcs?.find {
        it.attribute === "cname"
    } ?: throw Exception("CNAME value not found")
    rtpParameters.rtcp.cname = ssrcCnameLine.value

    // Standard simylcast based on a=simulcast and RID.
    if (!planBSimulcast) {
        // Get first (and may be the only one) ssrc.
        val ssrc = ssrcCnameLine.id

        // Get a=rid lines.
        // Array of Objects with rid and profile keys.
        val simulcastStreams = arrayListOf<SimulcastStream>()
        val rids = section.rids
        if (rids != null && rids.isNotEmpty()) {
            for (rid in rids) {
                if (rid.direction !== "send")
                    continue

                if (Regex("^low").containsMatchIn(rid.id))
                    simulcastStreams.add(SimulcastStream(rid = rid.id, profile = "low"))
                else if (Regex("^medium").containsMatchIn(rid.id))
                    simulcastStreams.add(SimulcastStream(rid = rid.id, profile = "medium"))
                if (Regex("^high").containsMatchIn(rid.id))
                    simulcastStreams.add(SimulcastStream(rid = rid.id, profile = "high"))
            }
        }

        // Fill RTP parameters.
        rtpParameters.encodings = arrayListOf()

        if (simulcastStreams.size == 0) {
            val encoding = RtpEncoding(ssrc = ssrc)
            rtpParameters.encodings?.add(encoding)
        } else {
            for (simulcastStream in simulcastStreams) {
                val encoding = RtpEncoding(
                    encodingId = simulcastStream.rid,
                    profile = simulcastStream.profile
                )
                rtpParameters.encodings?.add(encoding)
            }
        }
    }
    // Simulcast based on PlanB.
    else {
        // First media SSRC (or the only one).
        var firstSsrc: Int? = null

        // Get all the SSRCs.
        val ssrcs = arrayListOf<Int>().toMutableSet()
        val ssrcList = section.ssrcs
        if (ssrcList != null && ssrcList.isNotEmpty()) {
            for (line in ssrcList) {
                if (line.attribute !== "msid")
                    continue

                val ssrc = line.id
                ssrcs.add(ssrc)

                if (firstSsrc == null)
                    firstSsrc = ssrc
            }

            if (ssrcs.size == 0)
                throw Exception("no a=ssrc lines found")
        }

        // Get media and RTX SSRCs.
        val ssrcToRtxSsrc = hashMapOf<Int, Int>()
        // First assume RTX is used.
        val ssrcGroups = section.ssrcGroups
        if (ssrcGroups != null && ssrcGroups.isNotEmpty()) {
            for (line in ssrcGroups) {
                if (line.semantics !== "FID")
                    continue

                val splitList = line.ssrcs.split(Regex("\\s+"))

                if (splitList.size == 2) {
                    val ssrc = splitList[0].toIntOrNull()
                    val rtxSsrc = splitList[1].toIntOrNull()
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
            // If the Set of SSRCs is not empty it means that RTX is not being used, so take
            // media SSRCs from there.
            for (ssrc in ssrcs) {
                ssrcToRtxSsrc.remove(ssrc)
            }
        }


        // Fill RTP parameters.
        rtpParameters.encodings = arrayListOf()

        val simulcast = ssrcToRtxSsrc.size > 1
        val simulcastProfiles = arrayListOf("low", "medium", "high")

        for ((ssrc, rtxSsrc) in ssrcToRtxSsrc) {
            val encoding = RtpEncoding(ssrc = ssrc)

            if (rtxSsrc > 0) {
                val rtx = hashMapOf<Int, Int>()
                rtx[ssrc] = rtxSsrc
                encoding.rtx = rtx
            }

            if (simulcast)
                encoding.profile = simulcastProfiles.removeAt(0)

            rtpParameters.encodings?.add(encoding)
        }
    }
}

fun addPlanBSimulcast(sdpObj: SessionDescription, track: MediaStreamTrack, mid: String? = null) {
    val section = findMediaSection(sdpObj, track, mid)

    // Get the SSRC.
    val ssrcMsidLine = section?.ssrcs?.find {
        it.attribute === "msid"
    } ?: throw Exception("a=ssrc line with msid information not found")

    val ssrc = ssrcMsidLine.id
    val msid = ssrcMsidLine.value?.split(' ')?.get(0)
    var rtxSsrc: Int = 0

    // Get the SSRC for RTX.
    section.ssrcGroups?.any {
        if (it.semantics !== "FID") return@any false
        val ssrcs = it.ssrcs.split(Regex("\\s+"))
        if (ssrcs.size == 2) {
            try {
                if (ssrcs[0].toInt() == ssrc) {
                    rtxSsrc = ssrcs[1].toInt()
                    return@any true
                }
            } catch (e: NumberFormatException) {

            }
        }
        return@any false
    }

    val ssrcCnameLine = section.ssrcs?.find {
        it.attribute === "cname" && it.id == ssrc
    } ?: throw Exception("CNAME line not found")

    val cname = ssrcCnameLine.value
    val ssrc2 = ssrc + 1
    val ssrc3 = ssrc + 2

    section.ssrcGroups?.add(
        MediaAttributes.SsrcGroup(
            semantics = "SIM",
            ssrcs = "$ssrc $ssrc2 $ssrc3"
        )
    )

    section.ssrcs?.add(
        MediaAttributes.Ssrc(
            id = ssrc,
            attribute = "cname",
            value = cname
        )
    )

    section.ssrcs?.add(
        MediaAttributes.Ssrc(
            id = ssrc,
            attribute = "msid",
            value = "$msid ${track.id()}"
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

    val rtxSsrc2 = rtxSsrc + 1
    val rtxSsrc3 = rtxSsrc + 2

    section.ssrcGroups?.add(
        MediaAttributes.SsrcGroup(
            semantics = "FID",
            ssrcs = "$ssrc $rtxSsrc"
        )
    )

    section.ssrcs?.add(
        MediaAttributes.Ssrc(
            id = rtxSsrc,
            attribute = "cname",
            value = cname
        )
    )

    section.ssrcs?.add(
        MediaAttributes.Ssrc(
            id = rtxSsrc,
            attribute = "msid",
            value = "$msid ${track.id()}"
        )
    )

    section.ssrcGroups?.add(
        MediaAttributes.SsrcGroup(
            semantics = "FID",
            ssrcs = "$ssrc2 $rtxSsrc2"
        )
    )

    section.ssrcs?.add(
        MediaAttributes.Ssrc(
            id = rtxSsrc2,
            attribute = "cname",
            value = cname
        )
    )

    section.ssrcs?.add(
        MediaAttributes.Ssrc(
            id = rtxSsrc2,
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
            id = rtxSsrc3,
            attribute = "cname",
            value = cname
        )
    )

    section.ssrcs?.add(
        MediaAttributes.Ssrc(
            id = rtxSsrc3,
            attribute = "msid",
            value = "$msid ${track.id()}"
        )
    )
}

fun findMediaSection(sdpObj: SessionDescription, track: MediaStreamTrack, mid: String?): SessionDescription.Media? {
    val mediaList = sdpObj.media
    if (mediaList.isNotEmpty()) {
        val section: SessionDescription.Media?
        if (mid !== null) {
            section = mediaList.find {
                it.mid == mid
            }
            if (section == null)
                throw Exception("SDP section with mid=$mid not found")
        } else {
            section = mediaList.find {
                val msidList = it.msid?.split(' ')
                it.type === track.kind() && msidList != null && msidList[1] === track.id()
            }
            if (section == null)
                throw  Exception("SDP section with a=msid containing track.id=${track.id()} not found")
        }
        return section
    }
    return null
}

data class Rtcp(
    var cname: String?,
    var reducedSize: Boolean?,
    var mux: Boolean?
)

data class SimulcastStream(
    var rid: String,
    var profile: String
)

data class RtpEncoding(
    var encodingId: String? = null,
    var profile: String? = null,
    var ssrc: Int? = null,
    var rtx: MutableMap<Int, Int>? = null
)

var rtpParametersMuxId: String? = null
var RTCRtpParameters.muxId: String?
    get() = rtpParametersMuxId
    set(value) {
        rtpParametersMuxId = value
    }

var rtpParametersEncodings: MutableCollection<RtpEncoding>? = null
var RTCRtpParameters.encodings: MutableCollection<RtpEncoding>?
    get() = rtpParametersEncodings
    set(value) {
        rtpParametersEncodings = value
    }

var rtcpParametersMux: Boolean? = null
var RTCRtcpParameters.mux: Boolean?
    get() = rtcpParametersMux
    set(value) {
        rtcpParametersMux = value
    }