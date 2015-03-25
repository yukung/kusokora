package jjug;

import org.bytedeco.javacpp.opencv_objdetect.CascadeClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.http.converter.BufferedImageHttpMessageConverter;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsMessagingTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.config.annotation.AbstractWebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import javax.servlet.http.Part;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.function.BiConsumer;

import static org.bytedeco.javacpp.opencv_core.*;

@SpringBootApplication
@RestController
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    public static final Logger log = LoggerFactory.getLogger(App.class);

    @Autowired
    FaceDetector faceDetector;
    @Autowired
    JmsMessagingTemplate jmsMessagingTemplate;
    @Autowired
    SimpMessagingTemplate simpMessagingTemplate;

    @Configuration
    @EnableWebSocketMessageBroker
    static class StompConfig extends AbstractWebSocketMessageBrokerConfigurer {

        @Override
        public void registerStompEndpoints(StompEndpointRegistry registry) {
            registry.addEndpoint("endpoint");   // WebSocket のエンドポイント
        }

        @Override
        public void configureMessageBroker(MessageBrokerRegistry registry) {
            registry.setApplicationDestinationPrefixes("/app"); // Controller に処理させる宛先の prefix
            // queue または topic を有効にする（両方可能）。queue は 1vs1(P2P)、topic は 1vsM(Pub-Sub)
            registry.enableSimpleBroker("/topic");
        }

        @Override
        public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
            // メッセージサイズの上限を10MBに上げる（デフォルトは 64KB）
            registration.setMessageSizeLimit(10 * 1024 * 1024);
        }
    }

    /*
     * HTTP リクエスト/レスポンスに BufferedImage を使えるようにする
     */
    @Bean
    BufferedImageHttpMessageConverter bufferedImageHttpMessageConverter() {
        return new BufferedImageHttpMessageConverter();
    }

    @RequestMapping(value = "/")
    String hello() {
        return "Hello World!";
    }

    @MessageMapping(value = "/greet")   // Controller 内の @MessageMapping が付いたメソッドがメッセージを受け取る
    @SendTo(value = "/topic/greetings")
        // 処理結果の送り先
    String greet(String name) {
        log.info("received {}", name);
        return "Hello " + name;
    }

    // curl -v -F 'file=@hoge.jpg' http://localhost:8080/duker > after.jpg という感じで使えるように
    @RequestMapping(value = "/duker", method = RequestMethod.POST)
    BufferedImage duker(@RequestParam Part file) throws IOException {
        Mat source = Mat.createFrom(ImageIO.read(file.getInputStream()));
        faceDetector.detectFaces(source, FaceTranslator::duker);
        return source.getBufferedImage();
    }

    @RequestMapping(value = "/send")
    String send(@RequestParam String msg) {
        Message<String> message = MessageBuilder.withPayload(msg).build();
        jmsMessagingTemplate.send("hello", message);
        return "OK";
    }

    @RequestMapping(value = "/queue", method = RequestMethod.POST)
    String queue(@RequestParam Part file) throws IOException {
        byte[] src = StreamUtils.copyToByteArray(file.getInputStream());
        Message<byte[]> message = MessageBuilder.withPayload(src).build();
        jmsMessagingTemplate.send("faceConverter", message);
        return "OK";
    }

//    @JmsListener(destination = "hello", concurrency = "1-5")
//    void handleHelloMessage(Message<String> message) {
//        log.info("received! {}", message);
//        log.info("msg={}", message.getPayload());
//    }

    @JmsListener(destination = "faceConverter", concurrency = "1-5")
    void convertFace(Message<byte[]> message) throws IOException {
        log.info("received! {}", message);
        try (InputStream stream = new ByteArrayInputStream(message.getPayload())) {
            Mat source = Mat.createFrom(ImageIO.read(stream));
            faceDetector.detectFaces(source, FaceTranslator::duker);
            BufferedImage image = source.getBufferedImage();

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", baos);
                baos.flush();
                // 画像を Base64 にエンコードしてメッセージ作成し、宛先 '/topic/faces' へメッセージ送信
                simpMessagingTemplate.convertAndSend("/topic/faces",
                        Base64.getEncoder().encodeToString(baos.toByteArray()));
            }
        }
    }
}

@Component
@Scope(value = "prototype", proxyMode = ScopedProxyMode.TARGET_CLASS)
class FaceDetector {
    // 分類器のパスをクラスパスから取得
    @Value("${classifierFile:classpath:/haarcascade_frontalface_default.xml}")
    File classifierFile;

    CascadeClassifier classifier;

    static final Logger log = LoggerFactory.getLogger(FaceDetector.class);

    public void detectFaces(Mat source, BiConsumer<Mat, Rect> detectAction) {
        Rect faceDetections = new Rect();
        // 顔認識実行
        classifier.detectMultiScale(source, faceDetections);
        // 認識した顔の数
        int numOfFaces = faceDetections.limit();
        log.info("{} faces are detected!", numOfFaces);
        for (int i = 0; i < numOfFaces; i++) {
            Rect r = faceDetections.position(i);
            // 認識結果を変換処理にかける
            detectAction.accept(source, r);
        }
    }

    /*
     * DI でプロパティがセットされたあとに classifier インスタンスを生成するため
     * ここで初期化処理をする
     */
    @PostConstruct
    void init() throws IOException {
        if (log.isInfoEnabled()) {
            log.info("load {}", classifierFile.toPath());
        }
        // 分類器の読み込み
        this.classifier = new CascadeClassifier(classifierFile.toPath().toString());
    }
}

class FaceTranslator {
    public static void duker(Mat source, Rect r) {  // BiConsumer<Mat, Rect> で渡せるようにする
        int x = r.x(), y = r.y(), h = r.height(), w = r.width();
        // 上半分の黒四角
        rectangle(source, new Point(x, y), new Point(x + w, y + h / 2), new Scalar(0, 0, 0, 0), -1, CV_AA, 0);
        // 下半分の白四角
        rectangle(source, new Point(x, y + h / 2), new Point(x + w, y + h), new Scalar(255, 255, 255, 0), -1, CV_AA, 0);
        // 中央の赤丸
        circle(source, new Point(x + h / 2, y + h / 2), (w + h) / 12, new Scalar(0, 0, 255, 0), -1, CV_AA, 0);
    }
}
