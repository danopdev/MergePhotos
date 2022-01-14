package com.dan.mergephotos

import android.app.Activity
import android.content.Context
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties

/**
Settings: all public var fields will be saved
 */
class Settings( private val activity: Activity) {

    companion object {
        const val SAVE_FOLDER = "/storage/emulated/0/MergePhotos"
        const val IMG_SIZE_SMALL = 512
        const val DEFAULT_NAME = "output"
    }

}