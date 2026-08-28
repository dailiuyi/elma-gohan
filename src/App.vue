<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'

onLaunch(() => undefined)
</script>

<style>
html,
body,
#app {
  min-height: 100%;
  margin: 0;
  background: #f7f7f5;
}

page {
  --elma-custom-nav-offset: 120rpx;
  min-height: 100%;
  background: #f7f7f5;
  color: #171717;
  font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
}

view,
text,
button,
input {
  box-sizing: border-box;
}

button::after {
  border: 0;
}
</style>
