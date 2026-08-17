build:
	sam build

deploy:
	./deploy.sh

deploy-no-confirm:
	./deploy.sh --yes

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