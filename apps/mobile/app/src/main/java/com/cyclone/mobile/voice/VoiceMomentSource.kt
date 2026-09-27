package com.cyclone.mobile.voice

import com.cyclone.mobile.owner.MomentKind
import com.cyclone.mobile.owner.OwnerMoment

/** An Owner Moment as voice sees it. The moment stays the one source of truth; nothing here is stored. */
object VoiceMomentSource {
    fun of(moment: OwnerMoment?): VoiceMoment? {
        moment ?: return null
        val kind = when (moment.kind) {
            MomentKind.QUESTION -> VoiceMoment.Kind.QUESTION
            MomentKind.VALUES -> VoiceMoment.Kind.VALUES
            MomentKind.APPROVAL -> VoiceMoment.Kind.APPROVAL
            MomentKind.SECRET -> VoiceMoment.Kind.SECRET
            MomentKind.HANDOVER -> VoiceMoment.Kind.HANDOVER
        }
        // SECRET is never spoken or heard: not even its prompt.
        val text = if (kind == VoiceMoment.Kind.SECRET) "" else VoiceRedaction.spoken(moment.text)
        val id = moment.requestId ?: "${moment.taskId}:${moment.kind}:${moment.text.hashCode()}"
        return VoiceMoment(id, kind, text, moment.choices, moment.fields.map { it.label })
    }
}
