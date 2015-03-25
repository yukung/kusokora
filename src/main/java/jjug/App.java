package jjug;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.BiConsumer;

import static org.bytedeco.javacpp.opencv_core.*;

@SpringBootApplication
@RestController
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @RequestMapping(value = "/")
    String hello() {
        return "Hello World!";
    }
}

@Component
class FaceDetector {
    public void detectFaces(Mat source, BiConsumer<Mat, Rect> detectAction) {

    }
}

class FaceTranslator {
    public static void duker(Mat source, Rect r) {

    }
}
