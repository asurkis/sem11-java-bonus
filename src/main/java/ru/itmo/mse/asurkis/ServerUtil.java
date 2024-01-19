package ru.itmo.mse.asurkis;

/**
 * Общий код, не зависящий от реализации сервера
 */
public class ServerUtil {
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
}
