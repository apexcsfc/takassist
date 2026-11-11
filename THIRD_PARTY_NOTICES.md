# Third-party notices

## llama.cpp and ggml

The bundled model server (`app/src/main/jniLibs/arm64-v8a/`) is built from
llama.cpp (https://github.com/ggml-org/llama.cpp), release b11748.

MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

llama-server includes nlohmann/json (MIT):

MIT License

Copyright (c) 2013-2025 Niels Lohmann

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

The Snapdragon build links Qualcomm's OpenCL and Hexagon runtime components at run
time from the device's own `/vendor` partition; they are not redistributed.

## whisper.cpp

The bundled speech-to-text engine (`libwhispercli.so`, the whisper-cli program) is built
from whisper.cpp (https://github.com/ggml-org/whisper.cpp), MIT License,
Copyright (c) 2023-2026 The ggml authors. It shares the ggml library (MIT) with llama.cpp.

The speech model is not bundled: `ggml-base.en-q5_1.bin` is placed in Download/TAKAssist
(e.g. by Knox Manage). It is OpenAI's Whisper base.en model (MIT License, Copyright (c)
2022 OpenAI) converted and quantized by the whisper.cpp project
(https://huggingface.co/ggerganov/whisper.cpp).

## OpenStreetMap data

The optional offline map files (`area.sqlite`, `route.bin`) and the online fallback
services use OpenStreetMap data, © OpenStreetMap contributors, available under the
Open Database License (ODbL): https://www.openstreetmap.org/copyright

## Language models

No model is bundled. Users supply their own GGUF model files, which are licensed by
their publishers (for example, Qwen 3.5 models are released under Apache 2.0).
