package xerus.monstercat.downloader

import javafx.concurrent.Task
import mu.KotlinLogging
import org.apache.http.HttpEntity
import org.apache.http.client.methods.HttpGet
import xerus.ktutil.*
import xerus.monstercat.api.APIConnection
import xerus.monstercat.api.response.MusicItem
import xerus.monstercat.api.response.Release
import xerus.monstercat.api.response.Track
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Path

fun getCover(coverUrl: String, size: Int? = null): HttpEntity =
	APIConnection.execute(HttpGet(
		coverUrl.replace("[", "%5B").replace("]", "%5D") + size?.let { "?image_width=$it" }.orEmpty())).entity

private inline val basePath
	get() = DOWNLOADDIR()

fun Track.toFileName() =
	toString(TRACKNAMEPATTERN()).replace(':', '꞉').replaceIllegalFileChars()

fun Release.downloadFolder(): Path = basePath.resolve(when {
	isMulti() -> toString(DOWNLOADDIRALBUM()).replaceIllegalFileChars() // Album, Monstercat Collection
	type == "Podcast" -> DOWNLOADDIRPODCAST()
	type == "Mixes" -> DOWNLOADDIRMIXES()
	else -> DOWNLOADDIRSINGLE()
})

fun Release.isMulti() = isMulti && tracks.size >= EPSTOSINGLES()

fun String.addFormatSuffix() = "$this.${QUALITY().split('_')[0]}"

private val logger = KotlinLogging.logger { }

abstract class Download(val item: MusicItem, val coverUrl: String) : Task<Long>() {
	
	init {
		@Suppress("LEAKINGTHIS")
		updateTitle(item.toString())
	}
	
	private var startTime: Long = 0
	protected var maxProgress: Double = 0.0
	override fun call(): Long {
		startTime = System.currentTimeMillis()
		download()
		return System.currentTimeMillis() - startTime
	}
	
	abstract fun download()
	
	protected fun downloadFile(inputStream: InputStream, path: Path, closeIn: Boolean = false, progress: (Long) -> Double) {
		val file = path.toFile()
		logger.trace("Downloading $file")
		updateMessage(file.name)
		
		val partFile = file.resolveSibling(file.name + ".part")
		inputStream.copyTo(FileOutputStream(partFile), closeIn, true) {
			updateProgress(progress(it), maxProgress)
			isCancelled
		}
		if(isCancelled) {
			logger.trace("Download of $partFile cancelled, deleting: " + partFile.delete())
		} else {
			file.delete()
			logger.trace("Renamed $partFile to $file: " + partFile.renameTo(file))
		}
	}
	
	override fun toString(): String = "Download(item=$item)"
	
}

class ReleaseDownload(private val release: Release, private var tracks: Collection<Track>) : Download(release, release.coverUrl) {
	
	private var totalProgress = 0
	
	fun downloadTrack(releaseId: String, trackId: String, path: Path) {
		val connection = APIConnection("release", releaseId, "download").addQueries("method=download", "type=${QUALITY()}", "track=$trackId")
		val entity = connection.getResponse().entity
		val contentLength = entity.contentLength
		if(contentLength == 0L)
			throw EmptyResponseException(connection.uri.toString())
		val length = contentLength.toDouble()
		downloadFile(entity.content, path, true) {
			totalProgress + it / length
		}
	}
	
	override fun download() {
		if(tracks.isEmpty())
			return
		val folder = release.downloadFolder()
		val partFolder = folder.resolveSibling(folder.fileName.toString() + ".part")
		val downloadFolder =
			if(folder.exists() && !partFolder.exists() || folder == basePath)
				folder
			else
				partFolder
		downloadFolder.createDirs()
		val downloadCover = DOWNLOADCOVERS() == DownloadCovers.ALL || DOWNLOADCOVERS() == DownloadCovers.COLLECTIONS && release.isMulti
		val coverFraction = 0.2
		maxProgress = tracks.size + coverFraction * downloadCover.toInt()
		
		logger.debug("Downloading $release to $downloadFolder")
		tr@ for(track in tracks) {
			var filename = downloadFolder.resolve(track.toFileName().addFormatSuffix())
			if(track.isAlbumMix)
				when(ALBUMMIXES()) {
					AlbumMixes.SEPARATE -> filename = basePath.resolve(DOWNLOADDIRMIXES()).createDirs().resolve(track.toFileName().addFormatSuffix())
					AlbumMixes.EXCLUDE -> continue@tr
					else -> {
					}
				}
			downloadTrack(release.id, track.id, filename)
			if(isCancelled)
				break@tr
			totalProgress++
		}
		if(!isCancelled && downloadCover) {
			val coverName = release.toString(COVERPATTERN()).replaceIllegalFileChars() + "." + release.coverUrl.substringAfterLast('.')
			updateMessage(coverName)
			val entity = getCover(release.coverUrl, COVERARTSIZE())
			val length = entity.contentLength.toDouble()
			downloadFile(entity.content, downloadFolder.resolve(coverName), true) {
				totalProgress + (coverFraction * it / length)
			}
		}
		
		if(!isCancelled && downloadFolder == partFolder) {
			// this Release has not been downloaded to the root, thus delete the original subfolder if it exists and move the part-folder to it
			folder.toFile().deleteRecursively()
			try {
				partFolder.renameTo(folder)
			} catch(e: Exception) {
				logger.debug("Couldn't rename $partFolder to $folder as Paths: $e\nUsing File: " + partFolder.toFile().renameTo(folder.toFile()))
			}
		}
	}
	
}

class EmptyResponseException(term: String) : Exception("No file found for $term!")
