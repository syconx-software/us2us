package com.syconx.us2us.live

import android.graphics.RectF
import com.syconx.us2us.domain.AvatarEdit
import com.syconx.us2us.domain.AvatarEditError
import com.syconx.us2us.domain.AvatarPresetCatalog
import com.syconx.us2us.domain.MessengerException
import com.syconx.us2us.runtime.facade.AppRuntime
import com.syconx.us2us.runtime.facade.AvatarSelection
import com.syconx.us2us.runtime.media.AvatarPhotoProcessingException
import kotlinx.coroutines.CancellationException

/**
 * Profile pictures (PROTOCOL.md §8.9, §8.10) over the runtime: the own choice. A 4.2 result's search
 * copy and a request's picture after “Profil resmini göster” are in [RealMessengerService]; copies
 * found by search stay in memory only and go when the app locks.
 */
object LiveAvatars {
    suspend fun setAvatar(runtime: AppRuntime, edit: AvatarEdit) {
        val selection = selection(edit)
        try {
            // “Aramada göster” stays as stored (never assumed on).
            runtime.setAvatar(selection)
        } catch (e: CancellationException) {
            throw e
        } catch (_: AvatarPhotoProcessingException) {
            throw MessengerException.AvatarEdit(AvatarEditError.UnusablePhoto)
        } catch (_: Exception) {
            throw MessengerException.AvatarEdit(AvatarEditError.Failed)
        }
    }

    /** What the runtime is asked to set: only presets this build's catalogue lists are ever sent (§8.9). */
    fun selection(edit: AvatarEdit): AvatarSelection = when (edit) {
        AvatarEdit.Initials -> AvatarSelection.Initials
        is AvatarEdit.Preset -> {
            if (AvatarPresetCatalog.preset(edit.id) == null) throw MessengerException.AvatarEdit(AvatarEditError.Failed)
            AvatarSelection.Preset(edit.id)
        }
        is AvatarEdit.Photo -> {
            val crop = edit.crop
            if (edit.data.isEmpty() || crop.width <= 0 || crop.height <= 0) throw MessengerException.AvatarEdit(AvatarEditError.UnusablePhoto)
            AvatarSelection.Photo(
                edit.data.toByteArray(),
                RectF(crop.x.toFloat(), crop.y.toFloat(), (crop.x + crop.width).toFloat(), (crop.y + crop.height).toFloat()),
            )
        }
    }
}
