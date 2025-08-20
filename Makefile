build:
	sam build

deploy: build
	sam deploy --guided

deploy-no-confirm: build
	sam deploy --no-confirm-changeset

logs:
	sam logs -n SlackBotFunctionNative --stack-name slack-ai-assistant --tail

test:
	./gradlew test

clean:
	./gradlew clean
	rm -rf .aws-sam

build-SlackBotFunctionNative:
	./build-native.sh
	cp ./build/native/slack-ai-assistant $(ARTIFACTS_DIR)/bootstrap

.PHONY: build deploy deploy-no-confirm logs test clean