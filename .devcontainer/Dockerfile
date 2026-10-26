FROM howardlau1999/yatcpu

RUN apt-get update \
	&& DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
		gcc-riscv64-unknown-elf \
		binutils-riscv64-unknown-elf \
	&& rm -rf /var/lib/apt/lists/*