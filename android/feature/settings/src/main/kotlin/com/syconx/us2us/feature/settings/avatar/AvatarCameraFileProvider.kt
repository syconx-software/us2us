package com.syconx.us2us.feature.settings.avatar

import androidx.core.content.FileProvider

/**
 * The FileProvider of the avatar camera's capture folder. A class of its own, so it merges beside
 * the media module's provider (the manifest merger tells providers apart by class name).
 */
class AvatarCameraFileProvider : FileProvider()
