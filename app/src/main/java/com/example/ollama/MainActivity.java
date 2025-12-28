@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    TextView tv = new TextView(this);
    tv.setText("Starting...");
    tv.setTextSize(16);
    setContentView(tv);

    new Thread(() -> {
        String url =
            "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v0.3-GGUF/resolve/main/"
            + "tinyllama-1.1b-chat-v0.3.Q4_K_M.gguf";

        String fileName = "tinyllama.gguf";

        String modelPath = downloadFileToInternalStorage(url, fileName);

        String result;
        if (modelPath == null) {
            result = "Download failed";
        } else {
            LlamaNative llama = new LlamaNative();
            result = llama.init(modelPath);
        }

        String finalResult = result;

        runOnUiThread(() -> {
            tv.setText(finalResult);
        });

    }).start();
}
