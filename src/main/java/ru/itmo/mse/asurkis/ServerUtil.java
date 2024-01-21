package ru.itmo.mse.asurkis;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import ru.itmo.mse.asurkis.Messages.ArrayMessage;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Общий код, не зависящий от реализации сервера
 */
public class ServerUtil {
    public static final int START_CAPACITY_BYTES = 1024;

    public static int findCapacity(int currCapacity, int requiredSize) {
        while (currCapacity < requiredSize) currCapacity *= 2;
        return currCapacity;
    }

    public static ByteBuffer ensureLimit(ByteBuffer buf, int limit) {
        int newCapacity = findCapacity(buf.capacity(), limit);
        if (newCapacity == buf.capacity()) {
            buf.clear();
        } else {
            buf = ByteBuffer.allocate(newCapacity);
        }
        buf.limit(limit);
        return buf;
    }

    public static ByteBuffer processPayload(ByteBuffer buffer) throws IOException {
        buffer.flip();
        ArrayMessage payload = ArrayMessage.parseFrom(buffer);
        payload = ServerUtil.processPayload(payload);

        buffer = ensureLimit(buffer, 4 + payload.getSerializedSize());
        buffer.putInt(payload.getSerializedSize());
        CodedOutputStream cos = CodedOutputStream.newInstance(buffer);
        payload.writeTo(cos);
        cos.flush();

        buffer.flip();
        return buffer;
    }

    /**
     * Распаковать массив, прочитать и упаковать.
     * Все эти операции происходят в памяти, без ввода-вывода,
     * поэтому будут выполняться в worker'ах.
     */
    public static ArrayMessage processPayload(ArrayMessage payload) {
        int[] arr = new int[payload.getXCount()];
        for (int i = 0; i < arr.length; i++)
            arr[i] = payload.getX(i);

        sortInPlace(arr);

        ArrayMessage.Builder builder = ArrayMessage.newBuilder();
        for (int x : arr) builder.addX(x);
        return builder.build();
    }

    /**
     * Отсортировать массив, который пришёл по сети, за квадратичное время
     */
    public static void sortInPlace(int[] arr) {
        for (int i = 0; i < arr.length; i++) {
            for (int j = i + 1; j < arr.length; j++) {
                if (arr[i] > arr[j]) {
                    int t = arr[i];
                    arr[i] = arr[j];
                    arr[j] = t;
                }
            }
        }
    }

    public static void printMetrics(Metrics metrics) {
        synchronized (System.out) {
            System.out.printf("%d,%d\n",
                    metrics.processingFinish - metrics.processingStart,
                    metrics.responseSent - metrics.requestReceived);
        }
    }
}
