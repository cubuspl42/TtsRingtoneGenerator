package app

import com.google.cloud.texttospeech.v1.AudioConfig
import com.google.cloud.texttospeech.v1.AudioEncoding
import com.google.cloud.texttospeech.v1.SynthesisInput
import com.google.cloud.texttospeech.v1.TextToSpeechClient
import com.google.cloud.texttospeech.v1.VoiceSelectionParams
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.StringWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class AudioBuffer(
    val sampleData: ByteArray,
) {
    companion object {
        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            44100f,
            16,
            2,
            4,
            44100f,
            false,
        )

        fun fromData(
            data: ByteArray,
        ): AudioBuffer {
            return convertAudioInputStream(
                AudioSystem.getAudioInputStream(
                    ByteArrayInputStream(data),
                ),
            )
        }

        fun fromFile(
            file: File,
        ): AudioBuffer {
            return convertAudioInputStream(
                AudioSystem.getAudioInputStream(file),
            )
        }

        private fun readSamples(
            sampleStream: InputStream,
        ): AudioBuffer {
            return AudioBuffer(
                sampleData = sampleStream.readBytes(),
            )
        }

        private fun convertAudioInputStream(
            stream: AudioInputStream,
        ): AudioBuffer {
            val convertedStream = AudioSystem.getAudioInputStream(format, stream)

            return readSamples(convertedStream)
        }

        fun silence(
            duration: Duration,
        ): AudioBuffer {
            val sampleData = ByteArray(
                format.frameSize * (format.frameRate * duration.seconds).roundToInt(),
            )

            return AudioBuffer(
                sampleData = sampleData,
            )
        }
    }

    private fun buildStream(): AudioInputStream = AudioInputStream(
        ByteArrayInputStream(this.sampleData),
        format,
        (sampleData.size / format.frameSize).toLong(),
    )

    fun take(
        duration: Duration,
    ): AudioBuffer {
        val startFrame = 0
        val endFrame = (duration.seconds * format.frameRate).roundToLong()

        return convertAudioInputStream(
            AudioInputStream(
                ByteArrayInputStream(sampleData),
                format,
                endFrame - startFrame,
            ),
        )
    }

    fun concatWith(
        other: AudioBuffer,
    ): AudioBuffer {
        val thisStream = buildStream()
        val otherStream = other.buildStream()

        return readSamples(
            SequenceInputStream(thisStream, otherStream),
        )
    }

    fun writeToFile(file: File) {
        AudioSystem.write(
            buildStream(),
            AudioFileFormat.Type.WAVE,
            file,
        )
    }
}

val voice: VoiceSelectionParams = VoiceSelectionParams.newBuilder().apply {
    languageCode = "pl-PL"
    name = "pl-PL-Wavenet-E"
}.build()

val audioConfig: AudioConfig = AudioConfig.newBuilder().apply {
    audioEncoding = AudioEncoding.LINEAR16
    sampleRateHertz = 16000
    speakingRate = 0.7
}.build()

val favoriteContactNames = listOf(
    "Renia",
    "Ania",
    "Ewa",
    "Sara",
    "Kuba",
    "Hania",
    "Marek",
)

val ringtonePath: Path = Paths.get("ringtone.wav")

val outDirPath: Path = Paths.get("out")

fun main() {
    val client = TextToSpeechClient.create()

    val silence1 = AudioBuffer.silence(
        duration = Duration.ofMillis(500),
    )

    val silence2 = AudioBuffer.silence(
        duration = Duration.ofMillis(1000),
    )


    val ringtoneAudioBuffer = AudioBuffer.fromFile(
        ringtonePath.toFile(),
    )

    val ringtoneShortAudioBuffer = ringtoneAudioBuffer.take(
        duration = Duration.ofSeconds(3),
    )

    favoriteContactNames.forEach { contactName ->
        val synthesisAudioBuffer = AudioBuffer.fromData(
            synthesizeContactName(
                client = client,
                contactName = contactName,
            ),
        )

        val outputAudioBuffer =
            ringtoneShortAudioBuffer.concatWith(silence1).concatWith(synthesisAudioBuffer).concatWith(silence2)
                .concatWith(ringtoneAudioBuffer)

        outDirPath.toFile().mkdirs()

        val filePath = outDirPath.resolve("$contactName-dzwonek.wav")

        outputAudioBuffer.writeToFile(filePath.toFile())
    }
}

private fun synthesizeContactName(
    client: TextToSpeechClient,
    contactName: String,
): ByteArray {
    val ssmlDocument = newDocument().apply {
        appendChild(
            createElement("speak").apply {
                appendChild(
                    createTextNode("Dzwoni ")
                )
                appendChild(
                    createTextNode(contactName),
                )
                appendChild(
                    createTextNode("!")
                )
            },
        )
    }

    val input = SynthesisInput.newBuilder().apply {
        ssml = ssmlDocument.dumpToString()
    }.build()

    val response = client.synthesizeSpeech(input, voice, audioConfig)

    return response.audioContent.toByteArray()
}

private fun newDocument(): Document {
    val documentBuilderFactory = DocumentBuilderFactory.newInstance()
    val documentBuilder = documentBuilderFactory.newDocumentBuilder()

    return documentBuilder.newDocument()
}

private fun Document.dumpToString(): String {
    val transformerFactory = TransformerFactory.newInstance()
    val transformer = transformerFactory.newTransformer()

    return StringWriter().use {
        transformer.transform(DOMSource(this), StreamResult(it))
        it.buffer.toString()
    }
}
