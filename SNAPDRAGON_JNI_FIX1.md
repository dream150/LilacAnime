# Snapdragon JNI Fix 1

Fixed the JNI dynamic-symbol table so `LLAMA_SYM(name)` resolves against fields with the exact exported llama.cpp symbol names.

The previous wrapper declared shortened fields such as `backend_init` and `model_load`, while the loader macro attempted `g.llama_backend_init` and `g.llama_model_load_from_file`. This caused compile-time errors before linking.

No translation prompt/context/batch behavior was changed.
