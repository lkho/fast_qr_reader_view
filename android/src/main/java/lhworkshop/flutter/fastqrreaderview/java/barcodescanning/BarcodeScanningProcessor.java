// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package lhworkshop.flutter.fastqrreaderview.java.barcodescanning;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.ResultPointCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class BarcodeScanningProcessor {

    private static final String TAG = "BarcodeScanProc";

    private final MultiFormatReader detector;
    private final HashMap<DecodeHintType, Object> hints;

    public OnCodeScanned callback;
    public ResultPointCallback resultPointCallback;

    // Whether we should ignore process(). This is usually caused by feeding input data faster than
    // the model can handle.
    public final AtomicBoolean shouldThrottle = new AtomicBoolean(true);

    public BarcodeScanningProcessor(ArrayList<BarcodeFormat> reqFormats) {
        detector = new MultiFormatReader();
        hints = new HashMap<>();
        hints.put(DecodeHintType.POSSIBLE_FORMATS, reqFormats);
        detector.setHints(hints);
    }

    public void stop() {
        detector.reset();
    }


    public void detectInImage(BinaryBitmap image) {
        if (shouldThrottle.get()) {
            return;
        }
        shouldThrottle.set(true);
        try {
            if (resultPointCallback != null) {
                hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK, resultPointCallback);
            } else {
                hints.remove(DecodeHintType.NEED_RESULT_POINT_CALLBACK);
            }
            Result result = detector.decodeWithState(image);
            shouldThrottle.set(false);
            if (callback != null) callback.onCodeScanned(result);
        } catch (Exception ignored) {
            shouldThrottle.set(false);
        }
    }
}

