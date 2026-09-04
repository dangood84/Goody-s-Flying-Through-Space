SRC := $(wildcard src/main/java/com/goody/screensaver/*.java)
OUT := out
MAIN := com.goody.screensaver.StarfieldSaver

.PHONY: compile run config screensaver clean

compile:
	mkdir -p $(OUT)
	javac --release 21 -encoding UTF-8 -d $(OUT) $(SRC)

run: config

config: compile
	java -cp $(OUT) $(MAIN) --config

screensaver: compile
	java -cp $(OUT) $(MAIN) --fullscreen

clean:
	rm -rf $(OUT)
