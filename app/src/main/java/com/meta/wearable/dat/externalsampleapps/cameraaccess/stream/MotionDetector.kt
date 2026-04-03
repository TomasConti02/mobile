import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class MotionDetector {
    private var lastFrameGrep: Mat? = null
    private val movementThreshold = 5000.0 // Numero di pixel modificati per considerare "movimento"

    fun isSceneMoving(bitmap: Bitmap): Boolean {
        val currentFrame = Mat()
        val grayFrame = Mat()
        
        // 1. Converti Bitmap in Mat di OpenCV
        Utils.bitmapToMat(bitmap, currentFrame)
        
        // 2. Converti in scala di grigi per velocizzare i calcoli
        Imgproc.cvtColor(currentFrame, grayFrame, Imgproc.COLOR_RGBA2GRAY)
        
        // Applica un leggero blur per ridurre il rumore del sensore
        Imgproc.GaussianBlur(grayFrame, grayFrame, Size(21.0, 21.0), 0.0)

        if (lastFrameGrep == null) {
            lastFrameGrep = grayFrame
            return false
        }

        // 3. Calcola la differenza tra il frame precedente e quello attuale
        val frameDelta = Mat()
        Core.absdiff(lastFrameGrep, grayFrame, frameDelta)
        
        // 4. Soglia: i pixel con differenza > 25 diventano bianchi (255)
        val thresh = Mat()
        Imgproc.threshold(frameDelta, thresh, 25.0, 255.0, Imgproc.THRESH_BINARY)

        // 5. Conta i pixel bianchi (movimento)
        val movementAmount = Core.countNonZero(thresh)
        
        // Aggiorna l'ultimo frame per il prossimo ciclo
        lastFrameGrep?.release()
        lastFrameGrep = grayFrame

        // Pulizia memoria temporanea
        currentFrame.release()
        frameDelta.release()
        thresh.release()

        return movementAmount > movementThreshold
    }
}
