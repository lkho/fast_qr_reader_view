package lhworkshop.flutter.fastqrreaderview.java.barcodescanning;

import com.google.zxing.Result;

public interface OnCodeScanned {
    void onCodeScanned(Result barcode);
}
