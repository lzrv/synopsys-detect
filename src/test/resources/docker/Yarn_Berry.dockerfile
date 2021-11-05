FROM adoptopenjdk/openjdk11:jdk-11.0.6_10-ubuntu-slim

ENV SRC_DIR=/opt/project/src

RUN apt-get update
RUN apt-get install -y curl apt-transport-https make g++

# Install git
RUN apt-get install -y git

# Install npm
RUN curl -sL https://deb.nodesource.com/setup_current.x | bash -
RUN apt-get install -y nodejs

# Install yarn
RUN npm install -g yarn

RUN which node && which yarn && yarn --version && node --version

# Set up the test project
RUN mkdir -p ${SRC_DIR}

RUN git clone --depth 1 -b @yarnpkg/shell/2.4.1 https://github.com/yarnpkg/berry.git ${SRC_DIR}

#RUN cd ${SRC_DIR} \
   #&& git checkout 6b9df772ac785f73e6d08f0fc8c3f1718f296671 && yarn build:cli
