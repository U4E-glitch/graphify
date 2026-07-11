package com.dheyab.qiyas.data.db

import com.dheyab.qiyas.domain.model.BpContext
import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import com.dheyab.qiyas.domain.model.Zone

fun ReadingEntity.toDomain(): Reading = Reading(
    id = id,
    profileId = profileId,
    type = ReadingType.valueOf(type),
    measuredAt = measuredAt,
    createdAt = createdAt,
    glucoseMgdl = glucoseMgdl,
    glucoseContext = glucoseContext?.let { GlucoseContext.valueOf(it) },
    systolic = systolic,
    diastolic = diastolic,
    pulse = pulse,
    bpContext = bpContext?.let { BpContext.valueOf(it) },
    note = note,
    zone = Zone.valueOf(zone),
    photoPath = photoPath,
)

fun Reading.toEntity(): ReadingEntity = ReadingEntity(
    id = id,
    profileId = profileId,
    type = type.name,
    measuredAt = measuredAt,
    createdAt = createdAt,
    glucoseMgdl = glucoseMgdl,
    glucoseContext = glucoseContext?.name,
    systolic = systolic,
    diastolic = diastolic,
    pulse = pulse,
    bpContext = bpContext?.name,
    note = note,
    zone = zone.name,
    photoPath = photoPath,
)
