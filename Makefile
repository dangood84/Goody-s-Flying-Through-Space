SRC := $(wildcard src/main/java/com/goody/screensaver/*.java)
OUT := out
MAIN := com.goody.screensaver.StarfieldSaver

.PHONY: compile run config screensaver window jar clean

compile:
	mkdir -p $(OUT)
	javac --release 21 -encoding UTF-8 -d $(OUT) $(SRC)

run: config

config: compile
	java -cp $(OUT) $(MAIN) --config

screensaver: compile
	java -cp $(OUT) $(MAIN) --fullscreen

window: compile
	java -cp $(OUT) $(MAIN) --window

jar: compile
	jar cfe GoodysStarfield.jar $(MAIN) -C $(OUT) .
	@echo "Built GoodysStarfield.jar (java -jar GoodysStarfield.jar)"

clean:
	rm -rf $(OUT) GoodysStarfield.jar
